;;
;; MIT License
;; Copyright (c) 2024 Defold
;; Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
;; The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;;

(ns editor.texturepacker
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.build-target :as bt]
            [editor.colors :as colors]
            [editor.core :as core]
            [editor.defold-project :as project]
            [editor.dialogs :as dialogs]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.pass :as pass]
            [editor.gl.shader :as shader]
            [editor.gl.texture :as texture]
            [editor.gl.vertex2 :as vtx]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.math :as math]
            [editor.outline :as outline]
            [editor.pipeline :as pipeline]
            [editor.pipeline.tex-gen :as tex-gen]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.scene-picking :as scene-picking]
            [editor.texture-set :as texture-set]
            [editor.types :as types]
            [editor.util :as util]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.java :as java]
            [schema.core :as s]
            [util.digestable :as digestable])
  (:import [com.dynamo.bob.pipeline AtlasUtil ShaderUtil$Common ShaderUtil$VariantTextureArrayFallback]
           [com.dynamo.bob.textureset TextureSetLayout TextureSetLayout$Layout TextureSetLayout$Page TextureSetLayout$Rectangle TextureSetLayout$SourceImage]
           [com.dynamo.gamesys.proto Tile$Playback]
           [com.dynamo.gamesys.proto TextureSetProto$TextureSet]
           [com.dynamo.graphics.proto Graphics$TextureImage Graphics$TextureProfile]
           [com.jogamp.opengl GL GL2]
           [editor.gl.vertex2 VertexBuffer]
           [editor.types AABB Animation Image]
           [java.io File]
           [java.lang IllegalArgumentException]
           [java.lang.reflect Method]
           [java.util List]
           [javax.vecmath Matrix4d Point3d Vector3d]))

(def tpinfo-icon "/texturepacker/editor/resources/icons/32/icon-tpinfo.png")
(def tpatlas-icon "/texturepacker/editor/resources/icons/32/icon-tpatlas.png")
(def animation-icon "/texturepacker/editor/resources/icons/32/icon-animation.png")
(def image-icon "/texturepacker/editor/resources/icons/32/icon-image.png")

(def tpinfo-file-ext "tpinfo")
(def tpatlas-file-ext "tpatlas")

;; Plugin functions (from Atlas.java)

(def tpinfo-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Atlas"))
(def tpinfo-page-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Page"))
(def tpatlas-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Atlas$AtlasDesc"))
(def tp-plugin-cls (workspace/load-class! "com.dynamo.bob.pipeline.tp.Atlas"))

(def byte-array-cls (Class/forName "[B"))

(defn- debug-cls [^Class cls]
  (doseq [^Method m (.getMethods cls)]
    (prn (.toString m))
    (println "Method Name: " (.getName m) "(" (.getParameterTypes m) ")")
    (println "Return Type: " (.getReturnType m) "\n")))
;; TODO: Support printing public variables as well

(defn- plugin-invoke-static [^Class cls name types args]
  (let [^Method method (try
                         (java/get-declared-method cls name types)
                         (catch NoSuchMethodException error
                           (debug-cls cls)
                           (throw error)))
        obj-args (into-array Object args)]
    (try
      (.invoke method nil obj-args)
      (catch IllegalArgumentException error
        (prn "ERROR calling method:" (.toString method))
        (prn "    with args of types:" (map type obj-args))
        (throw error)))))

(defn- plugin-create-layout-page
  ^TextureSetLayout$Page [^long page-index tpinfo-page-pb]
  (plugin-invoke-static tp-plugin-cls "createLayoutPage"
                        [Integer/TYPE tpinfo-page-pb-cls]
                        [(int page-index) tpinfo-page-pb]))

;; Creates an instance of tp-plugin-cls, using both tpinfo and tpatlas data (for use with the TPAtlasNode)
(defn- plugin-create-full-atlas [^String path ^bytes tpatlas-as-bytes ^bytes tpinfo-as-bytes]
  (plugin-invoke-static tp-plugin-cls "createFullAtlas"
                        [String byte-array-cls byte-array-cls]
                        [path tpatlas-as-bytes tpinfo-as-bytes]))

;; Returns a Pair<TextureSet, List<TextureSetGenerator.UVTransform>> (.left, .right)
(defn- plugin-create-texture-set-result [^String path atlas ^String texture-path]
  (plugin-invoke-static tp-plugin-cls "createTextureSetResult"
                        [String tp-plugin-cls String]
                        [path atlas texture-path]))

;; Creates the final texture (com.dynamo.graphics.proto.Graphics.TextureImage)
(defn- plugin-create-texture
  ^Graphics$TextureImage [^String path is-paged buffered-images ^Graphics$TextureProfile texture-profile-pb compress]
  (plugin-invoke-static tp-plugin-cls "createTexture"
                        [String Boolean/TYPE List Graphics$TextureProfile Boolean/TYPE]
                        [path is-paged buffered-images texture-profile-pb compress]))

;; Returns a float array (2-tuples) that is a triangle list: [t0.x0, t0.y0, t0.x1, t0.y1, t0.x2, t0.y2, t1.x0, t1.y0, ...]
(defn- plugin-source-image-get-vertices [^TextureSetLayout$SourceImage source-image page-height]
  (plugin-invoke-static tp-plugin-cls "getTriangles"
                        [TextureSetLayout$SourceImage Float]
                        [source-image page-height]))

(g/deftype ^:private NameCounts {s/Str s/Int})
(g/deftype ^:private LayoutPageVec [TextureSetLayout$Page])
(g/deftype ^:private LayoutVec [TextureSetLayout$Layout])

;; This is for rendering atlas texture pages!
(defn- get-rect-page-offset [page-width page-index]
  (let [page-margin 32]
    (+ (* page-margin page-index) (* page-width page-index))))

(defn- get-atlas-aabb [page-width page-height num-pages]
  (let [page-margin 32
        width (+ (* (- num-pages 1) page-margin) (* page-width num-pages))
        height page-height]
    [width height]))

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0)
  (vec1 page_index))

(defn gen-page-rect-vertex-buffer [width height page-index]
  (let [page-offset-x (get-rect-page-offset width page-index)
        x0 page-offset-x
        y0 0
        x1 (+ width page-offset-x)
        y1 height
        v0 [x0 y0 0 1 0 0 page-index]
        v1 [x0 y1 0 1 0 1 page-index]
        v2 [x1 y1 0 1 1 1 page-index]
        v3 [x1 y0 0 1 1 0 page-index]
        ^VertexBuffer vbuf (->texture-vtx 6)
        ^ByteBuffer buf (.buf vbuf)]
    (doto buf
      (vtx/buf-push-floats! v0)
      (vtx/buf-push-floats! v1)
      (vtx/buf-push-floats! v2)
      (vtx/buf-push-floats! v2)
      (vtx/buf-push-floats! v3)
      (vtx/buf-push-floats! v0))
    (vtx/flip! vbuf)))

(defn- array-sampler-name->uniform-names [array-sampler-uniform-name page-count]
  (mapv #(str array-sampler-uniform-name "_" %) (range page-count)))

(shader/defshader pos-uv-vert
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (attribute float page_index)
  (varying vec2 var_texcoord0)
  (varying float var_page_index)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)
    (setq var_page_index page_index)))

(shader/defshader pos-uv-frag
  (varying vec2 var_texcoord0)
  (varying float var_page_index)
  (uniform sampler2DArray texture_sampler)
  (defn void main []
    (setq gl_FragColor (texture2DArray texture_sampler (vec3 var_texcoord0.xy var_page_index)))))

(def atlas-shader
  (let [transformed-shader-result (ShaderUtil$VariantTextureArrayFallback/transform pos-uv-frag ShaderUtil$Common/MAX_ARRAY_SAMPLERS)
        augmented-fragment-source (.source transformed-shader-result)
        array-sampler-names (vec (.arraySamplers transformed-shader-result))
        array-sampler-uniform-names (into {}
                                          (map (fn [item] [item (array-sampler-name->uniform-names item ShaderUtil$Common/MAX_ARRAY_SAMPLERS)])
                                               array-sampler-names))]
    (shader/make-shader ::atlas-shader pos-uv-vert augmented-fragment-source {} array-sampler-uniform-names)))

(defn- render-atlas
  [^GL2 gl render-args [renderable] _renderable-count]
  (let [{:keys [pass]} render-args]
    (condp = pass
      pass/transparent
      (let [{:keys [user-data]} renderable
            {:keys [vbuf]} user-data
            gpu-texture (or (get user-data :gpu-texture) @texture/white-pixel)
            vertex-binding (vtx/use-with ::atlas-binding vbuf atlas-shader)]
        (gl/with-gl-bindings gl render-args [atlas-shader vertex-binding gpu-texture]
          (shader/set-samplers-by-index atlas-shader gl 0 (:texture-units gpu-texture))
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 6))))))

(defn- render-atlas-outline
  [^GL2 gl render-args [renderable] _renderable-count]
  (let [{:keys [pass]} render-args]
    (condp = pass
      pass/outline
      (let [{:keys [aabb]} renderable
            [x0 y0] (math/vecmath->clj (types/min-p aabb))
            [x1 y1] (math/vecmath->clj (types/max-p aabb))
            [cr cg cb ca] colors/outline-color]
        (.glColor4d gl cr cg cb ca)
        (.glBegin gl GL2/GL_QUADS)
        (.glVertex3d gl x0 y0 0)
        (.glVertex3d gl x0 y1 0)
        (.glVertex3d gl x1 y1 0)
        (.glVertex3d gl x1 y0 0)
        (.glEnd gl)))))

(defn- get-rect-transform [width page-index]
  (let [page-offset (get-rect-page-offset width page-index)]
    (doto (Matrix4d.)
      (.setIdentity)
      (.setTranslation (Vector3d. page-offset 0.0 0.0)))))

(defn- make-page-renderable [^TextureSetLayout$Page layout-page gpu-texture]
  (let [size (.size layout-page)
        page-index (.index layout-page)
        page-width (.width size)
        page-height (.height size)
        aabb (types/->AABB (Point3d. 0 0 0) (Point3d. page-width page-height 0))]
    {:aabb aabb
     :transform (get-rect-transform page-width page-index)
     :renderable {:render-fn render-atlas
                  :user-data {:gpu-texture gpu-texture
                              :vbuf (gen-page-rect-vertex-buffer page-width page-height page-index)}
                  :tags #{:atlas}
                  :passes [pass/transparent]}
     :children (concat [{:aabb aabb
                         :renderable {:render-fn render-atlas-outline
                                      :tags #{:atlas :outline}
                                      :passes [pass/outline]}}])}))

(g/defnk produce-tpinfo-scene [_node-id size layout-pages child-scenes gpu-texture]
  (let [[width height] size
        child-renderables (mapv #(make-page-renderable % gpu-texture)
                                layout-pages)]
    {:info-text (format "TexturePacker File (.tpinfo): %d pages %d x %d" (count layout-pages) (int width) (int height))
     :children (into child-renderables
                     child-scenes)}))

(g/defnk produce-tpatlas-scene [_node-id size texture-profile tpinfo tpinfo-scene child-scenes]
  (let [[width height] size]
    (if (nil? tpinfo-scene)
      {:info-text (format "Atlas: 0 pages 0 x 0")}
      {:info-text (format "Atlas: %d pages %d x %d (%s profile)" (count (:pages tpinfo)) (int width) (int height) (:name texture-profile "no"))
       :children (into [tpinfo-scene] child-scenes)})))

(g/defnk produce-tpinfo-save-value [page-image-names tpinfo]
  ;; The user might have moved or renamed the page image files in the project.
  ;; Ensure the page image names are up-to-date with the project structure.
  (let [pages (:pages tpinfo)]
    (if (empty? pages)
      tpinfo
      (let [pages-with-up-to-date-image-names
            (mapv (fn [page page-image-name]
                    (assoc page :name page-image-name))
                  pages
                  page-image-names)]
        (assoc tpinfo :pages pages-with-up-to-date-image-names)))))

(defn- size->vec2 [{:keys [width height]}]
  [width height])

(defn- content-generator? [value]
  (and (map? value)
       (ifn? (:f value))
       (map? (:args value))
       (digestable/sha1-hash? (:sha1 value))))

(defn- call-content-generator [content-generator]
  ((:f content-generator) (:args content-generator)))

(defn- make-gpu-texture [request-id page-image-content-generators texture-profile]
  (let [buffered-images (mapv call-content-generator page-image-content-generators)]
    (g/precluding-errors buffered-images
      (let [texture-images
            (mapv #(tex-gen/make-preview-texture-image % texture-profile)
                  buffered-images)]
        (texture/texture-images->gpu-texture
          request-id
          texture-images
          {:min-filter gl/nearest
           :mag-filter gl/nearest})))))

(g/defnode TPInfoNode
  (inherits resource-node/ResourceNode)
  (inherits outline/OutlineNode)

  (property tpinfo g/Any (dynamic visible (g/constantly false))) ; Loaded tpinfo. Use save-value instead when you need up-to-date resource paths.
  (property frame-ids g/Any (dynamic visible (g/constantly false)))
  (property layout-pages LayoutPageVec (dynamic visible (g/constantly false)))
  (property layouts LayoutVec (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [tpinfo] (some-> tpinfo :pages first :size size->vec2)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property version g/Str
            (value (g/fnk [tpinfo] (:version tpinfo)))
            (dynamic read-only? (g/constantly true)))

  (property description g/Str
            (value (g/fnk [tpinfo] (:description tpinfo)))
            (dynamic read-only? (g/constantly true)))

  (input page-image-names g/Str :array)

  (input page-image-content-generators g/Any :array)
  (output page-image-content-generators g/Any (gu/passthrough page-image-content-generators))

  (output path g/Str (g/fnk [resource] (resource/path resource)))

  (input images g/Any :array)
  (output images g/Any (gu/passthrough images))

  (output parent-dir-file File :cached
          (g/fnk [resource]
            ;; This is used to convert page image proj-paths to "page names" relative to the `.tpinfo` file.
            (-> resource
                (resource/proj-path) ; proj-path works with zip resources, and we're only interested in the path here.
                (io/file)
                (.getParentFile))))

  (output frame-ids g/Any :cached
          (g/fnk [tpinfo]
            (into [] ; TODO: Use sorted set?
                  (mapcat (fn [page]
                            (map :name (:sprites page))))
                  (:pages tpinfo))))

  (output aabb AABB
          (g/fnk [size tpinfo]
            (if (= [0 0] size)
              geom/null-aabb
              (let [page-count (count (:pages tpinfo))
                    [w h] size
                    [width height] (get-atlas-aabb w h page-count)]
                (types/->AABB (Point3d. 0 0 0) (Point3d. width height 0))))))

  (output gpu-texture g/Any :cached
          (g/fnk [_node-id page-image-content-generators]
            (make-gpu-texture _node-id page-image-content-generators nil)))

  (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

  (input child-scenes g/Any :array)
  (input child-outlines g/Any :array)
  (input child-build-errors g/Any :array)

  (output scene g/Any :cached produce-tpinfo-scene)

  (output build-errors g/Any
          (g/fnk [_node-id child-build-errors]
            (g/package-errors _node-id child-build-errors)))

  (output node-outline outline/OutlineData
          (g/fnk [_node-id path child-outlines build-errors]
            {:node-id _node-id
             :node-outline-key path
             :label path
             :icon tpinfo-icon
             :read-only true
             :outline-error? (g/error-fatal? build-errors)
             :children child-outlines}))

  (output save-value g/Any :cached produce-tpinfo-save-value))

(defn- render-image-geometry [^GL2 gl vertices offset-x color]
  (let [[cr cg cb ca] color]
    (.glColor4d gl cr cg cb ca)
    (.glBegin gl GL2/GL_TRIANGLES)
    (doseq [[x y] vertices]
      (.glVertex3d gl (+ x offset-x) y 0))
    (.glEnd gl)))

(defn- render-image-outlines
  [^GL2 gl render-args renderables _renderable-count]
  (assert (= (:pass render-args) pass/outline))
  (doseq [renderable renderables]
    (let [user-data (-> renderable :user-data)
          page-offset-x (get-rect-page-offset (:layout-width user-data) (:page-index user-data))
          color (colors/renderable-outline-color renderable)
          image (:image user-data)
          vertices (:vertices image)]
      (render-image-geometry gl vertices page-offset-x color)))
  (doseq [renderable renderables]
    (let [user-data (-> renderable :user-data)
          page-offset-x (get-rect-page-offset (:layout-width user-data) (:page-index user-data))
          image (:image user-data)
          vertices (:vertices image)]
      (when (= (-> renderable :updatable :state :frame) (:order user-data))
        (render-image-geometry gl vertices page-offset-x colors/defold-pink)))))

(defn- render-image-selection
  [^GL2 gl render-args renderables n]
  (assert (= (:pass render-args) pass/selection))
  (assert (= n 1))
  (let [renderable (first renderables)
        picking-id (:picking-id renderable)
        id-color (scene-picking/picking-id->color picking-id)
        user-data (-> renderable :user-data)
        page-offset-x (get-rect-page-offset (:layout-width user-data) (:page-index user-data))
        image (:image user-data)
        vertices (:vertices image)]
    (render-image-geometry gl vertices page-offset-x id-color)))

(defn- atlas-rect->editor-rect [rect]
  (types/->Rect (:path rect) (:x rect) (:y rect) (:width rect) (:height rect)))

;; Flips the y -> page-height - y
(defn- to-rect [name page-height rect]
  {:path name :x (:x rect) :y (- page-height (:y rect) (:height rect)) :width (:width rect) :height (:height rect)})

(defn- get-scene-from-image [name-to-image-map name]
  (let [image-node (get name-to-image-map name) ;  get the source image
        scene (g/node-value image-node :scene)] ;; TODO: This use of g/node-value bypasses the dependency invalidation system. Put scenes in name-to-image-map?
    scene))

;; TODO:
;; ImageNode.scene -> TPInfoNode.image-scenes
;; TPInfoNode.page-scenes -> TPAtlasNode.page-scenes
;; TPInfoNode.image-scenes -> TPAtlasNode.image-scenes
;; TPAtlasNode.scene replaces node-ids in image-scenes with frame node-ids
;; No need to produce duplicate scenes from animations, right?
;; Should we have a separate frame node type?

(defn- make-scene-from-image [_node-id image image-order ^TextureSetLayout$Page layout-page animation-updatable]
  (let [size (.size layout-page)
        page-width (.width size)
        page-height (.height size)
        rect (to-rect name page-height (:rect image))
        editor-rect (atlas-rect->editor-rect rect)
        aabb (geom/rect->aabb editor-rect)
        page-index (.index layout-page)]
    {:node-id _node-id
     :aabb aabb
     :renderable {:render-fn render-image-outlines
                  :tags #{:atlas :outline}
                  :batch-key ::atlas-image
                  :user-data {:image image
                              :rect rect
                              :order image-order
                              :layout-width page-width
                              :layout-height page-height
                              :page-index page-index}
                  :passes [pass/outline]}
     :children [{:aabb aabb
                 :node-id _node-id
                 :renderable {:render-fn render-image-selection
                              :tags #{:atlas}
                              :user-data {:image image
                                          :rect rect
                                          :layout-width page-width
                                          :layout-height page-height
                                          :page-index page-index}
                              :passes [pass/selection]}}]
     :updatable animation-updatable}))

(g/defnk produce-image-scene [_node-id name name-to-image-map image image-order layout-page animation-updatable]
  (let [scene (if (nil? layout-page)
                (get-scene-from-image name-to-image-map name)
                (make-scene-from-image _node-id image image-order layout-page animation-updatable))]
    (assoc scene :node-id _node-id)))

(defn- rename-id [id rename-patterns]
  (if rename-patterns
    (try (AtlasUtil/replaceStrings rename-patterns id) (catch Exception _ id))
    id))

(defn prop-id-missing-in? [id ids]
  (when-not (contains? (set ids) id)
    (format "'%s' could not be found in .tpinfo file" id)))

(defn- validate-name [node-id name names]
  (validation/prop-error :fatal node-id :name prop-id-missing-in? name names))

(g/defnode AtlasAnimationImage
  (inherits outline/OutlineNode)

  (property original-name g/Str
            (dynamic visible (g/constantly false))
            (dynamic read-only? (g/constantly true)))

  (property name g/Str
            (value (g/fnk [original-name rename-patterns] (rename-id original-name rename-patterns)))
            (dynamic error (g/fnk [_node-id name image-names] (validate-name _node-id name image-names)))
            (dynamic read-only? (g/constantly true)))

  ;; see source-image->map below for some reference to this map
  (property image g/Any (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [image]
                     (when image
                       (let [rect (:rect image)
                             width (:width rect)
                             height (:height rect)]
                         [width height]))))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic visible (g/fnk [is-animation-child] (not is-animation-child)))
            (dynamic read-only? (g/constantly true)))

  (input layout-page TextureSetLayout$Page)
  (input rename-patterns g/Str)

  (input image-names g/Any) ; a list of original image names (i.e. not renamed)
  (input name-to-image-map g/Any) ; a map from original image names to source image nodes

  (input animation-updatable g/Any)

  (input child->order g/Any)

  (output is-animation-child g/Bool (g/fnk [child->order] (some? child->order)))

  ;; The order of this image within the list of images in the actual .tpinfo file
  (output image-order g/Any (g/fnk [original-name ^List image-names] (.indexOf image-names original-name)))

  (output order g/Any (g/fnk [_node-id child->order] (child->order _node-id))) ; it is a child of an AnimationNode

  (output ddf-message g/Any (g/fnk [original-name order]
                              {:image original-name :order order}))

  (output scene g/Any produce-image-scene)

  (output node-outline outline/OutlineData (g/fnk [_node-id is-animation-child name build-errors]
                                             {:node-id _node-id
                                              :node-outline-key name
                                              :label name
                                              :icon image-icon
                                              :read-only (not is-animation-child)
                                              :outline-error? (g/error-fatal? build-errors)}))

  (output build-errors g/Any (g/fnk [_node-id name image-names]
                               (g/package-errors _node-id (validate-name _node-id name image-names)))))

;; See TextureSetLayer$Page
(g/defnode AtlasPageNode
  (inherits core/Scope)
  (inherits outline/OutlineNode)

  (property layout-page TextureSetLayout$Page (dynamic visible (g/constantly false)))

  (property size types/Vec2 ; TODO: Derive from layout-page.
            (value (g/fnk [width height] [width height]))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property image resource/Resource
            (value (gu/passthrough image-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :image-resource]
                                            [:content-generator :image-content-generator])))
            (dynamic error (g/fnk [_node-id image]
                             (validation/prop-error :fatal _node-id :image validation/prop-resource-not-exists? image "Image"))))

  (input tpinfo-parent-dir-file File) ; Used to convert the page image resource proj-path into a "page name" relative to the `.tpinfo` file.

  (input child-outlines g/Any :array)
  (input child-build-errors g/Any :array)
  (input image-resource resource/Resource)

  (input image-content-generator g/Any)
  (output image-content-generator g/Any (gu/passthrough image-content-generator))

  (output width g/Num (g/fnk [layout-page] (.width (.size layout-page)))) ; TODO: Just use size?
  (output height g/Num (g/fnk [layout-page] (.height (.size layout-page))))
  (output label g/Any :cached (g/fnk [name width height] (format "%s (%d x %d)" name (int width) (int height))))

  (output name g/Str :cached (g/fnk [image-resource tpinfo-parent-dir-file]
                               (if (nil? image-resource)
                                 ""
                                 (let [image-proj-path (resource/proj-path image-resource)
                                       image-file (io/file image-proj-path)]
                                   (resource/relative-path tpinfo-parent-dir-file image-file)))))

  ;; TODO: Not needed, right?
  (output own-build-errors g/Any (g/fnk [_node-id image]
                                   (g/package-errors _node-id
                                                     (validation/prop-error :fatal _node-id :scene validation/prop-resource-not-exists? image "Image"))))

  (output build-errors g/Any (g/fnk [_node-id child-build-errors own-build-errors]
                               (g/package-errors _node-id
                                                 child-build-errors
                                                 own-build-errors)))

  (output node-outline outline/OutlineData (g/fnk [_node-id name label child-outlines own-build-errors]
                                             {:node-id _node-id
                                              :node-outline-key name
                                              :label label
                                              :icon tpatlas-icon
                                              :read-only true
                                              :outline-error? (g/error-fatal? own-build-errors)
                                              :children child-outlines})))

(defn- rectangle->map [^TextureSetLayout$Rectangle rectangle]
  {:x (.x rectangle)
   :y (.y rectangle)
   :width (.width rectangle)
   :height (.height rectangle)})

(defn- source-image->map [^TextureSetLayout$SourceImage source-image page-height]
  {:name (.name source-image)
   :original-size (some-> (.originalSize source-image) rectangle->map)
   :rect (rectangle->map (.rect source-image))
   :rotated (boolean (.rotated source-image))
   :vertices (into [] ; vertices is an array of floats (2-tuples) describing a triangle list (every 3 vertices form a triangle)
                   (partition-all 2)
                   (plugin-source-image-get-vertices source-image page-height))
   :indices (into (vector-of :int)
                  (.indices source-image))})

(defn- add-image-node [tpinfo-id page-id ^TextureSetLayout$Page layout-page ^TextureSetLayout$SourceImage source-image]
  (let [name (.name source-image)
        page-height (.width (.size layout-page))
        parent-graph-id (g/node-id->graph-id page-id)
        image-map (source-image->map source-image page-height)]
    (g/make-nodes parent-graph-id [image-id [AtlasAnimationImage :original-name name :image image-map]]
      (g/connect image-id :_node-id page-id :nodes)
      (g/connect image-id :node-outline page-id :child-outlines)
      (g/connect image-id :_node-id tpinfo-id :images)
      (g/connect image-id :scene tpinfo-id :child-scenes)
      (g/connect page-id :layout-page image-id :layout-page)
      (g/connect tpinfo-id :frame-ids image-id :image-names))))

(defn- add-page-node [tpinfo-id page-image-resource ^TextureSetLayout$Page layout-page]
  (let [parent-graph-id (g/node-id->graph-id tpinfo-id)]
    (g/make-nodes parent-graph-id [page-id [AtlasPageNode :layout-page layout-page :image page-image-resource]]
      (g/connect tpinfo-id :parent-dir-file page-id :tpinfo-parent-dir-file)
      (g/connect page-id :_node-id tpinfo-id :nodes)
      (g/connect page-id :node-outline tpinfo-id :child-outlines)
      (g/connect page-id :build-errors tpinfo-id :child-build-errors)
      (g/connect page-id :name tpinfo-id :page-image-names)
      (g/connect page-id :image-content-generator tpinfo-id :page-image-content-generators)
      (for [source-image (.images layout-page)]
        (add-image-node tpinfo-id page-id layout-page source-image)))))

;; Loads the .tpinfo file (api is default ddf loader)
(defn- load-tpinfo-file [_project self resource tpinfo]
  (let [pages (:pages tpinfo)

        page-image-resources
        (mapv (fn [page]
                (workspace/resolve-resource resource (:name page)))
              pages)

        layout-pages
        (into []
              (map-indexed (fn [index page]
                             (let [tpinfo-page-pb (protobuf/map->pb tpinfo-page-pb-cls page)]
                               (plugin-create-layout-page index tpinfo-page-pb))))
              pages)

        layouts (TextureSetLayout/createTextureSet layout-pages)]

    (concat
      (g/set-property self
        :tpinfo tpinfo
        :layout-pages layout-pages
        :layouts layouts)
      (mapcat
        (fn [page-image-resource layout-page]
          (add-page-node self page-image-resource layout-page))
        page-image-resources
        layout-pages))))

(defn- validate-tpinfo-file [_node-id resource]
  (or (validation/prop-error :fatal _node-id :file validation/prop-nil? resource "File")
      (validation/prop-error :fatal _node-id :file validation/prop-resource-not-exists? resource "File")))

;; *****************************************************************************

;; Attaches an AtlasAnimationImage node to an AtlasAnimation node
(defn- attach-image-to-animation [animation-node image-node]
  ;; TODO: This seems like an excessive number of connections? Surely some of this doesn't need to travel across the individual atlas animation images?
  (concat
    (g/connect image-node :_node-id animation-node :nodes)
    (g/connect image-node :build-errors animation-node :child-build-errors)
    (g/connect image-node :ddf-message animation-node :img-ddf)
    (g/connect image-node :node-outline animation-node :child-outlines)
    (g/connect image-node :scene animation-node :child-scenes)
    (g/connect animation-node :child->order image-node :child->order)
    (g/connect animation-node :name-to-image-map image-node :name-to-image-map)
    (g/connect animation-node :image-names image-node :image-names)
    (g/connect animation-node :updatable image-node :animation-updatable)
    (g/connect animation-node :rename-patterns image-node :rename-patterns)))

;; Attaches an AtlasAnimation to a TPAtlasNode
(defn- attach-animation-to-atlas [atlas-node animation-node]
  ;; TODO: This seems like an excessive number of connections? Surely some of this doesn't need to travel across the individual atlas animations?
  (concat
    (g/connect animation-node :_node-id atlas-node :nodes)
    (g/connect animation-node :animation atlas-node :animations)
    (g/connect animation-node :build-errors atlas-node :child-build-errors)
    (g/connect animation-node :ddf-message atlas-node :anim-ddf)
    (g/connect animation-node :id atlas-node :animation-ids)
    (g/connect animation-node :node-outline atlas-node :child-outlines)
    (g/connect animation-node :scene atlas-node :child-scenes)
    (g/connect atlas-node :id-counts animation-node :id-counts)
    (g/connect atlas-node :frame-ids animation-node :frame-ids)
    (g/connect atlas-node :name-to-image-map animation-node :name-to-image-map)
    (g/connect atlas-node :image-names animation-node :image-names)
    (g/connect atlas-node :rename-patterns animation-node :rename-patterns)
    (g/connect atlas-node :gpu-texture animation-node :gpu-texture)
    (g/connect atlas-node :anim-data animation-node :anim-data)))

;; *****************************************************************************
;; AtlasAnimation

(defn- sort-by-order-and-get-image [images]
  (->> images
       (sort-by :order)
       (mapv #(:image %))))

(defn- update-int->bool [keys m]
  (reduce (fn [m key]
            (if (contains? m key)
              (update m key (complement zero?))
              m))
          m
          keys))

(def ^:private default-animation
  {:flip-horizontal false
   :flip-vertical false
   :fps 24
   :playback :playback-loop-forward
   :id "New Animation"})

(defn- unique-id-error [node-id id id-counts]
  (or (validation/prop-error :fatal node-id :id validation/prop-empty? id "Id")
      (validation/prop-error :fatal node-id :id (partial validation/prop-id-duplicate? id-counts) id)))

(defn- validate-animation-id [node-id id id-counts]
  (unique-id-error node-id id id-counts))

(defn- validate-animation-fps [node-id fps]
  (validation/prop-error :fatal node-id :fps validation/prop-negative? fps "Fps"))

(g/defnk produce-anim-ddf [id fps flip-horizontal flip-vertical playback img-ddf]
  {:id id
   :fps fps
   :flip-horizontal flip-horizontal
   :flip-vertical flip-vertical
   :playback playback
   :images (sort-by-order-and-get-image img-ddf)})

(defn render-animation [^GL2 gl render-args renderables n]
  (texture-set/render-animation-overlay gl render-args renderables n ->texture-vtx atlas-shader))

(g/defnk produce-animation-updatable [_node-id id anim-data]
  (texture-set/make-animation-updatable _node-id "Atlas Animation" (get anim-data id)))

(g/defnk produce-animation-scene [_node-id id child-scenes gpu-texture updatable anim-data]
  {:node-id _node-id
   :aabb geom/null-aabb
   :renderable {:render-fn render-animation
                :tags #{:atlas}
                :batch-key nil
                :user-data {:gpu-texture gpu-texture
                            :anim-id id
                            :anim-data (get anim-data id)}
                :passes [pass/overlay pass/selection]}
   :updatable updatable
   :children child-scenes})

;; Structure that holds all information for an animation with multiple frames
(g/defnode AtlasAnimation
  (inherits core/Scope)
  (inherits outline/OutlineNode)

  (property id g/Str (dynamic error (g/fnk [_node-id id id-counts] (validate-animation-id _node-id id id-counts))))
  (property fps g/Int
            (default 24)
            (dynamic error (g/fnk [_node-id fps] (validate-animation-fps _node-id fps))))
  (property flip-horizontal g/Bool)
  (property flip-vertical g/Bool)
  (property playback types/AnimationPlayback
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Tile$Playback))))

  (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

  (input atlas-images Image :array)
  (output atlas-images [Image] (gu/passthrough atlas-images))

  ;; A map from id to id frequency (to detect duplicate names)
  (input id-counts NameCounts)
  ;; A list of the static frame ids from the texture packer
  (input frame-ids g/Any)
  ;; A map from source image name to the node id of the single frame AnimationImage nodes
  (input name-to-image-map g/Any)
  (output name-to-image-map g/Any (gu/passthrough name-to-image-map))

  (input image-names g/Any)
  (output image-names g/Any (gu/passthrough image-names))

  (input child-scenes g/Any :array)
  (input child-build-errors g/Any :array)
  (input anim-data g/Any)
  (input gpu-texture g/Any)

  (input rename-patterns g/Str)
  (output rename-patterns g/Str (gu/passthrough rename-patterns))

  (input gpu-texture g/Any)

  (output animation Animation (g/fnk [id atlas-images fps flip-horizontal flip-vertical playback]
                                (types/->Animation id atlas-images fps flip-horizontal flip-vertical playback)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id child-outlines id own-build-errors]
            {:node-id _node-id
             :node-outline-key id
             :label id
             :icon animation-icon
             :outline-error? (g/error-fatal? own-build-errors)
             :child-reqs [{:node-type AtlasAnimationImage
                           :tx-attach-fn attach-image-to-animation}]
             :children (sort-by :order child-outlines)}))

  (input img-ddf g/Any :array)
  (output ddf-message g/Any :cached produce-anim-ddf)
  (output updatable g/Any :cached produce-animation-updatable)
  (output scene g/Any :cached produce-animation-scene)
  (output own-build-errors g/Any (g/fnk [_node-id fps id id-counts]
                                   (g/package-errors _node-id
                                                     (validate-animation-id _node-id id id-counts)
                                                     (validate-animation-fps _node-id fps))))
  (output build-errors g/Any (g/fnk [_node-id child-build-errors own-build-errors]
                               (g/package-errors _node-id
                                                 child-build-errors
                                                 own-build-errors))))

(defn- make-image-nodes [attach-fn parent image-names]
  (let [graph-id (g/node-id->graph-id parent)]
    (for [image-name image-names]
      (g/make-nodes
        graph-id
        [atlas-image [AtlasAnimationImage {:original-name image-name}]]
        (attach-fn parent atlas-image)))))

(def ^:private make-image-nodes-in-animation (partial make-image-nodes attach-image-to-animation))

(defn- add-atlas-animation-node [atlas-node anim]
  (let [graph-id (g/node-id->graph-id atlas-node)
        image-names (:images anim)]
    (g/make-nodes
      graph-id
      [atlas-anim [AtlasAnimation :flip-horizontal (:flip-horizontal anim) :flip-vertical (:flip-vertical anim)
                   :fps (:fps anim) :playback (:playback anim) :id (:id anim)]]
      (concat
        (attach-animation-to-atlas atlas-node atlas-anim)
        (make-image-nodes-in-animation atlas-anim image-names)))))

;; .tpatlas file
(defn load-tpatlas-file [project self resource tpatlas]
  (let [tpinfo-resource (workspace/resolve-resource resource (:file tpatlas))
        tx-data (concat
                  (g/connect project :build-settings self :build-settings)
                  (g/connect project :texture-profiles self :texture-profiles)
                  (g/set-property self
                    :file tpinfo-resource
                    :tpatlas tpatlas
                    :rename-patterns (:rename-patterns tpatlas)
                    :is-paged-atlas (:is-paged-atlas tpatlas))
                  (mapv (fn [animation]
                          (->> animation
                               (update-int->bool [:flip-horizontal :flip-vertical])
                               (add-atlas-animation-node self)))
                        (:animations tpatlas)))]
    tx-data))

;; saving the .tpatlas file
(g/defnk produce-tpatlas-save-value [file anim-ddf rename-patterns is-paged-atlas]
  {:file (resource/resource->proj-path file)
   :rename-patterns rename-patterns
   :is-paged-atlas is-paged-atlas
   :animations anim-ddf})

(defn- validate-rename-patterns [node-id rename-patterns]
  (try
    (AtlasUtil/validatePatterns rename-patterns)
    (catch Exception error
      (validation/prop-error :fatal node-id :rename-patterns identity (.getMessage error)))))

(defn- tpinfo-has-multiple-pages? [tpinfo]
  (if (nil? tpinfo)
    false
    (> (count (:pages tpinfo)) 1)))

(defn- build-texture [resource _dep-resources user-data]
  (let [{:keys [page-image-content-generators]} user-data
        buffered-images (mapv call-content-generator page-image-content-generators)]
    (g/precluding-errors buffered-images
      (let [{:keys [paged-atlas texture-profile compress]} user-data
            path (resource/path resource)
            texture-profile-pb (some->> texture-profile (protobuf/map->pb Graphics$TextureProfile))
            texture-image-pb (plugin-create-texture path paged-atlas buffered-images texture-profile-pb compress)]
        {:resource resource
         :content (protobuf/pb->bytes texture-image-pb)}))))

(defn- make-texture-build-target
  [workspace node-id paged-atlas page-image-content-generators texture-profile compress]
  {:pre [(g/node-id? node-id)
         (boolean? paged-atlas)
         (coll? page-image-content-generators) ; Content generators for the page images: page-0.png, page-1.png etc
         (every? content-generator? page-image-content-generators)
         (or (nil? texture-profile) (map? texture-profile))
         (boolean? compress)]}
  (let [texture-type (workspace/get-resource-type workspace "texture")
        user-data {:page-image-content-generators (vec page-image-content-generators)
                   :texture-profile texture-profile
                   :paged-atlas paged-atlas
                   :compress compress}
        texture-resource (resource/make-memory-resource workspace texture-type user-data)]
    (bt/with-content-hash
      {:node-id node-id
       :resource (workspace/make-build-resource texture-resource)
       :build-fn build-texture
       :user-data user-data})))

(g/defnk produce-tpatlas-build-targets [_node-id resource build-errors tpinfo is-paged-atlas texture-set tpinfo-page-image-content-generators texture-profile build-settings]
  (g/precluding-errors build-errors
    (let [project (project/get-project _node-id)
          workspace (project/workspace project)
          use-paged-texture (or (tpinfo-has-multiple-pages? tpinfo) is-paged-atlas)
          compress (:compress-textures? build-settings false)
          page-image-content-generators (vec tpinfo-page-image-content-generators)
          texture-build-target (make-texture-build-target workspace _node-id use-paged-texture page-image-content-generators texture-profile compress)
          texture-resource (-> texture-build-target :resource :resource)
          pb-msg (protobuf/pb->map texture-set)
          dep-build-targets [texture-build-target]]
      [(pipeline/make-protobuf-build-target
         resource dep-build-targets
         TextureSetProto$TextureSet
         (assoc pb-msg :texture texture-resource)
         [:texture])])))

(g/defnk produce-anim-data [texture-set uv-transforms]
  (let [texture-set-pb-map (protobuf/pb->map texture-set)
        uv-transforms (into [] uv-transforms)]
    (texture-set/make-anim-data texture-set-pb-map uv-transforms)))

(s/defrecord AtlasRect
  [path :- s/Any
   x :- types/Int32
   y :- types/Int32
   width :- types/Int32
   height :- types/Int32
   page :- types/Int32])

(g/defnk produce-name-to-image-map [tpinfo-images]
  (let [node-ids tpinfo-images
        names (map (fn [id] (g/node-value id :name)) node-ids)
        name-image-map (zipmap names node-ids)]
    name-image-map))

(g/defnk produce-image-names [tpinfo-images rename-patterns]
  (let [node-ids tpinfo-images
        names (map (fn [id] (rename-id (g/node-value id :name) rename-patterns)) node-ids)]
    names))

(defn- modify-outline [rename-patterns outline]
  (let [modified (assoc outline :label (rename-id (:node-outline-key outline) rename-patterns))]
    modified))

;; We want to reuse the node outlines from the tpinfo file, but we also
;; need them to display any renamed image names
(defn- make-tpinfo-node-outline-copies [rename-patterns tpinfo-node-outline]
  (into []
        (comp (mapcat (fn [page-node-outline]
                        (:children page-node-outline)))
              (map (fn [image-node-outline]
                     (modify-outline rename-patterns image-node-outline))))
        (:children tpinfo-node-outline)))

(g/defnode TPAtlasNode
  (inherits resource-node/ResourceNode)

  (property file resource/Resource
            (value (gu/passthrough tpinfo-file-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :tpinfo-file-resource]
                                            [:node-outline :tpinfo-node-outline]
                                            [:save-value :tpinfo]
                                            [:size :tpinfo-size]
                                            [:images :tpinfo-images]
                                            [:frame-ids :tpinfo-frame-ids]
                                            [:page-image-content-generators :tpinfo-page-image-content-generators]
                                            [:build-errors :tpinfo-build-errors]
                                            [:scene :tpinfo-scene]
                                            [:aabb :aabb])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext tpinfo-file-ext}))
            (dynamic error (g/fnk [_node-id file]
                             (validate-tpinfo-file _node-id file))))

  (property tpatlas g/Any (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [tpinfo-size] tpinfo-size))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property rename-patterns g/Str
            (dynamic error (g/fnk [_node-id rename-patterns] (validate-rename-patterns _node-id rename-patterns))))

  ;; User setting, to manually choose if an atlas with a single page should use a texture array or not.
  (property is-paged-atlas g/Bool
            (dynamic visible (g/fnk [tpinfo] (not (tpinfo-has-multiple-pages? tpinfo)))))

  (input tpinfo-build-errors g/Any)
  (input tpinfo-file-resource resource/Resource)
  (input tpinfo-page-image-content-generators g/Any) ; A vector with a content-generator for each page image png file. Each generates a BufferedImage.
  (input tpinfo-node-outline g/Any)
  (input tpinfo-scene g/Any)
  (input tpinfo-images g/Any) ; node id's for each AtlasAnimationImage
  (input tpinfo-frame-ids g/Any) ; List of static frame id's from the .tpinfo file
  (output tpinfo-frame-ids g/Any (gu/passthrough tpinfo-frame-ids))

  (input tpinfo g/Any)
  (input tpinfo-size g/Any)

  (output use-texture-array g/Bool (g/fnk [tpinfo is-paged-atlas]
                                     (or (tpinfo-has-multiple-pages? tpinfo) is-paged-atlas)))

  (output texture-page-count g/Int (g/fnk [tpinfo use-texture-array]
                                     (if use-texture-array
                                       (count (:pages tpinfo))
                                       texture/non-paged-page-count)))

  (input build-settings g/Any)
  (input texture-profiles g/Any)

  (input animations Animation :array)
  (output name-to-image-map g/Any :cached produce-name-to-image-map)
  (output image-names g/Any :cached produce-image-names) ; These are a list of renamed source image names

  (output frame-ids g/Any :cached (g/fnk [tpinfo-frame-ids rename-patterns] (map (fn [id] (rename-id id rename-patterns)) tpinfo-frame-ids)))

  (output texture-set-result g/Any :cached
          ;; TODO: This is not fully configured. Rename to preview-texture-set-result or texture-set+uv-transforms?
          (g/fnk [_node-id resource save-value tpinfo tpinfo-file-resource]
            (or (validate-tpinfo-file _node-id tpinfo-file-resource)
                (let [path (resource/path resource)
                      tpatlas-bytes (protobuf/map->bytes tpatlas-pb-cls save-value)
                      tpinfo-bytes (protobuf/map->bytes tpinfo-pb-cls tpinfo)
                      atlas (plugin-create-full-atlas path tpatlas-bytes tpinfo-bytes)]
                  (plugin-create-texture-set-result path atlas "")))))

  (output uv-transforms g/Any (g/fnk [texture-set-result] (.right texture-set-result)))
  (output texture-set g/Any (g/fnk [texture-set-result] (.left texture-set-result)))

  (output anim-data g/Any :cached produce-anim-data)
  (input anim-ddf g/Any :array) ; Array of protobuf maps for each manually created animation
  (input animation-ids g/Str :array) ; List of the manually created animation ids

  (input child-scenes g/Any :array)
  (input child-build-errors g/Any :array)
  (input child-outlines g/Any :array)
  (input child-source-image-outlines g/Any :array)

  (input aabb AABB)
  (output aabb AABB (gu/passthrough aabb))

  (output texture-profile g/Any (g/fnk [texture-profiles resource]
                                  (tex-gen/match-texture-profile texture-profiles (resource/proj-path resource))))

  (output gpu-texture g/Any :cached
          (g/fnk [_node-id tpinfo-page-image-content-generators texture-profile]
            (make-gpu-texture _node-id tpinfo-page-image-content-generators texture-profile)))

  (output anim-ids g/Any :cached (g/fnk [animation-ids tpinfo-frame-ids] (filter some? (concat animation-ids tpinfo-frame-ids))))
  (output id-counts NameCounts :cached (g/fnk [anim-ids] (frequencies anim-ids)))

  (output node-outline outline/OutlineData :cached (g/fnk [_node-id tpinfo-node-outline rename-patterns child-outlines own-build-errors]
                                                     {:node-id _node-id
                                                      :node-outline-key "Atlas"
                                                      :label "Atlas"
                                                      :icon tpatlas-icon
                                                      :outline-error? (g/error-fatal? own-build-errors)
                                                      :children (into (make-tpinfo-node-outline-copies rename-patterns tpinfo-node-outline)
                                                                      child-outlines)}))

  (output save-value g/Any :cached produce-tpatlas-save-value)
  (output build-targets g/Any :cached produce-tpatlas-build-targets)
  (output updatable g/Any (g/fnk [] nil))
  (output scene g/Any :cached produce-tpatlas-scene)

  (output own-build-errors g/Any (g/fnk [_node-id file rename-patterns]
                                   (g/package-errors _node-id
                                                     (validate-tpinfo-file _node-id file)
                                                     (validate-rename-patterns _node-id rename-patterns))))

  (output build-errors g/Any (g/fnk [_node-id tpinfo-build-errors child-build-errors own-build-errors]
                               (g/package-errors _node-id
                                                 (g/unpack-errors tpinfo-build-errors)
                                                 child-build-errors
                                                 own-build-errors))))

;; *****************************************************************************
;; Outline handlers

(defn- selection->atlas [selection] (handler/adapt-single selection TPAtlasNode))
(defn- selection->animation [selection] (handler/adapt-single selection AtlasAnimation))
(defn- selection->image [selection] (handler/adapt-single selection AtlasAnimationImage))

(defn- add-animation-group-handler [app-view atlas-node]
  (let [op-seq (gensym)
        [animation-node] (g/tx-nodes-added
                           (g/transact
                             (concat
                               (g/operation-sequence op-seq)
                               (g/operation-label "Add Animation")
                               (add-atlas-animation-node atlas-node default-animation))))]
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (app-view/select app-view [animation-node])))))

(handler/defhandler :add :workbench
  (label [] "Add Animation")
  (active? [selection] (selection->atlas selection))
  (run [app-view selection] (add-animation-group-handler app-view (selection->atlas selection))))


(defn- add-images-handler [app-view animation-node]
  {:pre [(g/node-instance? AtlasAnimation animation-node)]}
  (let [frame-ids (sort util/natural-order
                        (g/node-value animation-node :frame-ids))
        frame-id-items (mapv (fn [frame-id]
                               {:text frame-id})
                             frame-ids)]
    (when-some [items (not-empty
                        (dialogs/make-select-list-dialog
                          frame-id-items
                          {:title "Select Animation Frames"
                           :selection :multiple
                           :ok-label "Add Animation Frames"}))]
      (let [op-seq (gensym)
            image-names (mapv :text items)
            image-nodes (g/tx-nodes-added
                          (g/transact
                            (concat
                              (g/operation-sequence op-seq)
                              (g/operation-label "Add Images")
                              (make-image-nodes-in-animation animation-node image-names))))]
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (app-view/select app-view image-nodes)))))))

(handler/defhandler :add-from-file :workbench
  (label [] "Add Animation Frames...")
  (active? [selection] (selection->animation selection))
  (run [app-view project selection workspace]
       (when-some [animation-node (selection->animation selection)]
         (add-images-handler app-view animation-node))))

(defn- vec-move
  [v x offset]
  (let [current-index (.indexOf ^java.util.List v x)
        new-index (max 0 (+ current-index offset))
        [before after] (split-at new-index (remove #(= x %) v))]
    (vec (concat before [x] after))))

(defn- move-node!
  [node-id offset]
  (let [parent (core/scope node-id)
        children (vec (g/node-value parent :nodes))
        new-children (vec-move children node-id offset)
        connections (keep (fn [[source source-label target target-label]]
                            (when (and (= source node-id)
                                       (= target parent))
                              [source-label target-label]))
                          (g/outputs node-id))]
    (g/transact
      (concat
        (for [child children
              [source target] connections]
          (g/disconnect child source parent target))
        (for [child new-children
              [source target] connections]
          (g/connect child source parent target))))))

(defn- move-active? [selection]
  (some->> selection
           selection->image
           core/scope
           (g/node-instance? AtlasAnimation)))

(handler/defhandler :move-up :workbench
  (active? [selection] (move-active? selection))
  (enabled? [selection] (let [node-id (selection->image selection)
                              parent (core/scope node-id)
                              ^List children (vec (g/node-value parent :nodes))
                              node-child-index (.indexOf children node-id)]
                          (pos? node-child-index)))
  (run [selection] (move-node! (selection->image selection) -1)))

(handler/defhandler :move-down :workbench
  (active? [selection] (move-active? selection))
  (enabled? [selection] (let [node-id (selection->image selection)
                              parent (core/scope node-id)
                              ^List children (vec (g/node-value parent :nodes))
                              node-child-index (.indexOf children node-id)]
                          (< node-child-index (dec (.size children)))))
  (run [selection] (move-node! (selection->image selection) 1)))

;; *****************************************************************************

(defn register-resource-types [workspace]
  (concat
    (resource-node/register-ddf-resource-type workspace
      :ext tpinfo-file-ext
      :label "Texture Packer Export File"
      :node-type TPInfoNode
      :load-fn load-tpinfo-file
      :icon tpinfo-icon
      :ddf-type tpinfo-pb-cls
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}})
    (resource-node/register-ddf-resource-type workspace
      :ext tpatlas-file-ext
      :build-ext "a.texturesetc"
      :label "Texture Packer Atlas"
      :node-type TPAtlasNode
      :ddf-type tpatlas-pb-cls
      :load-fn load-tpatlas-file
      :icon tpatlas-icon
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}}
      :template "/texturepacker/editor/resources/templates/template.tpatlas")))

;; The plugin
(defn load-plugin-texturepacker [workspace]
  (g/transact
    (concat
      (register-resource-types workspace)
      (workspace/register-resource-kind-extension workspace :atlas "tpatlas"))))

(defn return-plugin []
  (fn [x] (load-plugin-texturepacker x)))

(return-plugin)


;; TODO:
;; * Sort the images? Hmm. We don't do this for regular atlases.

;; DONE:
;; * Fix exception when clearing the `.tpinfo` File property in a `.tpatlas` with animations.
;; * Fix exception when dragging frames between animations in Outline.
;; * Fix exception when adding animation frames.
;; * Add the ability to add multiple animation frames at once.
;; * Add the ability to remove selected frames from an animation.
;; * Fix Outline panel icons for `.tpinfo` resources.
;; * The built texture now respects non-compression settings (such as mipmap) of the texture profile matched by the `.tpatlas` file.
;; * Removing a .png file from disk now results in a build error and on the Image property field.
;; * The Page Image resource field is now editable so users can patch up invalid page image references.
;; * Edits to a .png file from an external application now show up in scene views and the build output.
;; * Renaming a .png file now updates references in the `.tpinfo` file and the build output.
;; * Refactoring: Use map representation of `.tpinfo` instead of Protobuf Message object.
