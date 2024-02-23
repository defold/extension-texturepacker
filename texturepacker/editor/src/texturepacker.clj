;
; MIT License
; Copyright (c) 2024 Defold
; Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
; The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;

(ns editor.texturepacker
  (:require [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.colors :as colors]
            [editor.core :as core]
            [editor.dialogs :as dialogs]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.geom :as geom]
            [editor.math :as math]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.texture :as texture]
            [editor.gl.vertex :as vtx]
            [editor.gl.vertex2 :as vtx2]
            [editor.image-util :as image-util]
            [editor.defold-project :as project]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.scene-picking :as scene-picking]
    ;; [editor.render :as render]
            [editor.types :as types]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [editor.workspace :as workspace]
            [editor.gl.pass :as pass]
            [editor.types :as types]
            [editor.outline :as outline]
            [editor.properties :as properties]
            [editor.pipeline :as pipeline]
            [editor.pipeline.tex-gen :as tex-gen]
            [editor.resource-io :as resource-io]
            [editor.texture-set :as texture-set]
            [schema.core :as s]
            [util.digestable :as digestable])
  (:import [com.jogamp.opengl GL GL2]
           [java.awt.image BufferedImage]
           [java.lang IllegalArgumentException]
           [java.util List]
           [javax.vecmath Matrix4d Point3d Vector3d]
           [editor.types Animation Image AABB]
           [com.dynamo.gamesys.proto Tile$Playback]
           [com.dynamo.bob.pipeline AtlasUtil ShaderUtil$Common ShaderUtil$VariantTextureArrayFallback]
           [com.dynamo.graphics.proto Graphics$TextureImage Graphics$TextureProfile]
           [com.dynamo.gamesys.proto TextureSetProto$TextureSet]
           [com.dynamo.bob.textureset TextureSetLayout$SourceImage]
           [java.lang String]))

(set! *warn-on-reflection* true)

(def tpinfo-icon "/texturepacker/editor/resources/icons/32/icon-tpinfo.png")
(def tpatlas-icon "/texturepacker/editor/resources/icons/32/icon-tpatlas.png")
(def animation-icon "/texturepacker/editor/resources/icons/32/icon-animation.png")
(def image-icon "/texturepacker/editor/resources/icons/32/icon-image.png")

(def tpinfo-file-ext "tpinfo")
(def tpatlas-file-ext "tpatlas")

; Plugin functions (from Atlas.java)

(set! *warn-on-reflection* false)

(def tp-plugin-tpinfo-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Atlas"))
(def tp-plugin-tpatlas-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Atlas$AtlasDesc"))
(def tp-plugin-cls (workspace/load-class! "com.dynamo.bob.pipeline.tp.Atlas"))

(def byte-array-cls (Class/forName "[B"))
(def string-cls (Class/forName "java.lang.String"))
(def bufferedimage-array-cls (Class/forName "[Ljava.awt.image.BufferedImage;"))

(defn- debug-cls [^Class cls]
  (doseq [m (.getMethods cls)]
    (prn (.toString m))
    (println "Method Name: " (.getName m) "(" (.getParameterTypes m) ")")
    (println "Return Type: " (.getReturnType m) "\n")))
;; TODO: Support printing public variables as well

(set! *warn-on-reflection* false)
(defn- plugin-invoke-static [^Class cls name types args]
  (let [method (try
                 (.getMethod cls name types)
                 (catch NoSuchMethodException error
                   (debug-cls cls)
                   (throw error)))
        obj-args (into-array Object args)]
    (try
      (.invoke method nil obj-args)
      (catch IllegalArgumentException error
        (prn "ERROR calling method:" (.toString method))
        (prn "    with args of types:" (map type obj-args))
        (throw error)))
    ))
(set! *warn-on-reflection* true)

; return Atlas (Atlas.java)
(defn- plugin-create-atlas [path tpinfo-as-bytes]
  (plugin-invoke-static tp-plugin-cls "createAtlas" (into-array Class [String byte-array-cls]) [path tpinfo-as-bytes]))

(defn- plugin-create-full-atlas [path tpatlas-as-bytes tpinfo-as-bytes]
  (plugin-invoke-static tp-plugin-cls "createFullAtlas" (into-array Class [String byte-array-cls byte-array-cls]) [path tpatlas-as-bytes tpinfo-as-bytes]))

;  Returns a Pair<TextureSet, List<extureSetGenerator.UVTransform>> (.left, .right)
(defn- plugin-create-texture-set-result [path atlas texture-path]
  (plugin-invoke-static tp-plugin-cls "createTextureSetResult" (into-array Class [String tp-plugin-cls String]) [path atlas texture-path]))

(defn- plugin-create-texture ^Graphics$TextureImage [path is-paged atlas bufferedimages texture-profile]
  (plugin-invoke-static tp-plugin-cls "createTexture"
                        (into-array Class [String Boolean tp-plugin-cls bufferedimage-array-cls Graphics$TextureProfile])
                        [path is-paged atlas bufferedimages texture-profile]))

(defn- plugin-source-image-get-vertices [image page-height]
  (plugin-invoke-static tp-plugin-cls "getTriangles" (into-array Class [TextureSetLayout$SourceImage Float]) [image page-height]))


(g/deftype ^:private NameCounts {s/Str s/Int})

(set! *warn-on-reflection* false)


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

(vtx2/defvertex texture-vtx2
  (vec4 position)
  (vec2 texcoord0)
  (vec1 page_index))

(defn gen-page-rect-vertex-buffer [width height page-index]
  (let [page-offset-x (get-rect-page-offset width page-index)
        x0 page-offset-x
        y0 0
        x1 (+ width page-offset-x)
        y1 height]
    (persistent!
      (doto (->texture-vtx 6)
        (conj! [x0 y0 0 1 0 0 page-index])
        (conj! [x0 y1 0 1 0 1 page-index])
        (conj! [x1 y1 0 1 1 1 page-index])

        (conj! [x1 y1 0 1 1 1 page-index])
        (conj! [x1 y0 0 1 1 0 page-index])
        (conj! [x0 y0 0 1 0 0 page-index])))))

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
  [^GL2 gl render-args [renderable] n]
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
  [^GL2 gl render-args [renderable] n]
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

; page is of type TextureSetLayout$Page
(defn- produce-page-renderables [page gpu-texture]
  (let [size (.size page)
        page-index (.index page)
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

(g/defnk produce-tpinfo-scene [_node-id width height pages child-scenes gpu-texture]
  (let [num-pages (count pages)
        child-renderables (map (fn [page] (produce-page-renderables page gpu-texture)) pages)]
    {:info-text (format "TexturePacker File (.tpinfo): %d pages %d x %d" num-pages (int width) (int height))
     :children (into child-renderables
                     child-scenes)}))

(g/defnk produce-tpatlas-scene [_node-id size atlas texture-profiles tpinfo-scene child-scenes]
  (let [[width height] size]
    (if (nil? tpinfo-scene)
      {:info-text (format "Atlas: 0 pages 0 x 0")}
      {:info-text (format "Atlas: %d pages %d x %d (%s profile)" (count (.pages atlas)) (int width) (int height) (:name texture-profiles))
       :children (into [tpinfo-scene] child-scenes)})))

(set! *warn-on-reflection* true)


(g/defnode TPInfoNode
  (inherits resource-node/ResourceNode)
  (inherits outline/OutlineNode)

  (output path g/Str :cached (g/fnk [resource] (resource/path resource)))

  (property tpinfo g/Any (dynamic visible (g/constantly false)))
  (property atlas g/Any (dynamic visible (g/constantly false)))
  (property frame-ids g/Any (dynamic visible (g/constantly false)))

  (property pages g/Any (dynamic visible (g/constantly false))) ; type: TextureSetLayout.Page
  (property layouts g/Any (dynamic visible (g/constantly false))) ; type: TextureSetLayout.Layout
  (property animations g/Any (dynamic visible (g/constantly false)))
  (property page-names g/Any (dynamic visible (g/constantly false)))

  (property width g/Num (dynamic visible (g/constantly false)))
  (property height g/Num (dynamic visible (g/constantly false)))
  (property size types/Vec2
            (value (g/fnk [width height] [width height]))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property page-resources g/Any (dynamic visible (g/constantly false)))

  (input texture-profiles g/Any)

  (input images g/Any :array)
  (output images g/Any (gu/passthrough images))

  (output aabb AABB (g/fnk [size pages]
                      (if (= [0 0] size)
                        geom/null-aabb
                        (let [[w h] size
                              [width height] (get-atlas-aabb w h (count pages))]
                          (types/->AABB (Point3d. 0 0 0) (Point3d. width height 0))))))


  (output gpu-texture g/Any :cached (g/fnk [_node-id page-resources texture-profiles build-errors]
                                      (when (not (g/error-fatal? build-errors))
                                        (let [buffered-images (mapv #(resource-io/with-error-translation % _node-id nil
                                                                       (image-util/read-image %))
                                                                    page-resources)
                                              page-texture-images (mapv #(tex-gen/make-preview-texture-image % texture-profiles)
                                                                        buffered-images)]
                                          (texture/texture-images->gpu-texture
                                            _node-id
                                            page-texture-images
                                            {:min-filter gl/nearest
                                             :mag-filter gl/nearest})))))

  (input nodes g/Any :array)
  (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

  (input child-scenes g/Any :array)
  (input child-outlines g/Any :array)
  (input child-build-errors g/Any :array)

  (output scene g/Any :cached produce-tpinfo-scene)

  (output build-errors g/Any (g/fnk [_node-id child-build-errors]
                               (g/package-errors _node-id child-build-errors)))

  (output node-outline outline/OutlineData (g/fnk [_node-id path child-outlines build-errors]
                                             {:node-id _node-id
                                              :node-outline-key path
                                              :label path
                                              :icon animation-icon
                                              :outline-error? (g/error-fatal? build-errors)
                                              :children child-outlines
                                              :read-only true})))

(set! *warn-on-reflection* false)


(defn- render-rect [^GL2 gl rect color offset-x]
  (let [x0 (+ offset-x (:x rect))
        y0 (:y rect)
        x1 (+ x0 (:width rect))
        y1 (+ y0 (:height rect))
        [cr cg cb ca] color]
    (.glColor4d gl cr cg cb ca)
    (.glBegin gl GL2/GL_QUADS)
    (.glVertex3d gl x0 y0 0)
    (.glVertex3d gl x0 y1 0)
    (.glVertex3d gl x1 y1 0)
    (.glVertex3d gl x1 y0 0)
    (.glEnd gl)))

(defn- render-image-geometry [^GL2 gl vertices offset-x color]
  (let [[cr cg cb ca] color]
    (.glColor4d gl cr cg cb ca)
    (.glBegin gl GL2/GL_TRIANGLES)
    (doall (map (fn [vert] (let [[x y] vert]
                             (.glVertex3d gl (+ x offset-x) y 0))) vertices))
    (.glEnd gl)))

(defn render-image-outline
  [^GL2 gl render-args renderables]
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

(defn- render-image-outlines
  [^GL2 gl render-args renderables n]
  (condp = (:pass render-args)
    pass/outline
    (render-image-outline gl render-args renderables)))

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

; Flips the y -> page-height - y
(defn- to-rect [name page-height rect]
  {:path name :x (:x rect) :y (- page-height (:y rect) (:height rect)) :width (:width rect) :height (:height rect)})

(g/defnk produce-image-scene [_node-id name image image-order page animation-updatable]
  (let [size (.size page)
        page-width (.width size)
        page-height (.height size)
        rect (to-rect name page-height (:rect image))
        editor-rect (atlas-rect->editor-rect rect)
        aabb (geom/rect->aabb editor-rect)
        page-index (.index page)]
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

  ; see TextureSetLayout@SourceImage for some reference to this map
  (property image g/Any (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [image] (let [rect (:rect image)
                                        width (:width rect)
                                        height (:height rect)]
                                    [width height])))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (input page g/Any)
  (input rename-patterns g/Str)

  (input image-names g/Any) ; a list of original image names (i.e. not renamed)

  (input animation-updatable g/Any)

  (input child->order g/Any)

  ; The order of this image within the list of images in the actual .tpinfo file
  (output image-order g/Any (g/fnk [original-name image-names] (.indexOf image-names original-name)))

  (output order g/Any (g/fnk [_node-id child->order] (child->order _node-id))) ; it is a child of an AnimationNode

  (output ddf-message g/Any (g/fnk [original-name order]
                              {:image original-name :order order}))

  (output scene g/Any produce-image-scene)

  (output node-outline outline/OutlineData (g/fnk [_node-id name build-errors]
                                             {:node-id _node-id
                                              :node-outline-key name
                                              :label name
                                              :icon animation-icon
                                              :outline-error? (g/error-fatal? build-errors)
                                              :read-only true}))

  (output build-errors g/Any (g/fnk [_node-id name image-names]
                               (g/package-errors _node-id (validate-name _node-id name image-names)))))

; See TextureSetLayer$Page
(g/defnode AtlasPageNode
  (inherits outline/OutlineNode)
  (property name g/Str (dynamic visible (g/constantly false)))
  (property page g/Any (dynamic visible (g/constantly false)))
  (property file resource/Resource
            (dynamic read-only? (g/constantly true))
            (dynamic error (g/fnk [_node-id file]
                             (validation/prop-error :fatal _node-id :file validation/prop-resource-not-exists? file ".png"))))

  (output width g/Num :cached (g/fnk [page] (.width (.size page))))
  (output height g/Num :cached (g/fnk [page] (.height (.size page))))

  (property size types/Vec2
            (value (g/fnk [width height] [width height]))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (output label g/Any :cached (g/fnk [name width height] (format "%s (%d x %d)" name (int width) (int height))))

  (input nodes g/Any :array)
  (input child-outlines g/Any :array)
  (input child-build-errors g/Any :array)

  (output own-build-errors g/Any (g/fnk [_node-id file]
                                   (g/package-errors _node-id
                                                     (validation/prop-error :fatal _node-id :scene validation/prop-resource-not-exists? file "File"))))


  (output build-errors g/Any (g/fnk [_node-id child-build-errors own-build-errors]
                               (g/package-errors _node-id
                                                 child-build-errors
                                                 own-build-errors)))

  (output node-outline outline/OutlineData (g/fnk [_node-id name label child-outlines own-build-errors]
                                             {:node-id _node-id
                                              :node-outline-key name
                                              :label label
                                              :icon tpinfo-icon
                                              :children child-outlines
                                              :outline-error? (g/error-fatal? own-build-errors)
                                              :read-only true})))

(defn convert-source-image-to-map [image page-height]
  (let [out (dissoc (bean image) :class)
        ; vertices is an array of floats (2-tuples) describing a tringle list (every 3 vertices form a triangle)
        vertices (partition 2 (plugin-source-image-get-vertices image page-height))
        rect (dissoc (bean (:rect out)) :class)
        originalSize (:originalSize out)                    ; may be null
        originalSize (when (not (nil? originalSize))
                       (dissoc (bean originalSize) :class))]
    (assoc out
      :vertices vertices
      :rect rect
      :originalSize originalSize)))

(defn- create-image-node [tpinfo-id page-id page image]
  (let [name (.name image)
        page-height (.width (.size page))
        parent-graph-id (g/node-id->graph-id page-id)
        image-map (convert-source-image-to-map image page-height)
        image-tx-data (g/make-nodes parent-graph-id [image-id [AtlasAnimationImage :original-name name :image image-map]]
                        (g/connect image-id :_node-id page-id :nodes)
                        (g/connect image-id :node-outline page-id :child-outlines)
                        (g/connect image-id :_node-id tpinfo-id :images)
                        (g/connect image-id :scene tpinfo-id :child-scenes)
                        (g/connect page-id :page image-id :page)
                        (g/connect tpinfo-id :frame-ids image-id :image-names))]
    image-tx-data))

(defn- create-image-nodes [tpinfo-id parent-id page]
  (let [images (.images page)
        tx-data (mapcat (fn [image] (create-image-node tpinfo-id parent-id page image)) images)]
    tx-data))


(defn- tx-first-created [tx-data]
  (get-in (first tx-data) [:node :_node-id]))

(defn- create-page-node [tpinfo-id page parent-resource]
  (let [name (.name page)
        page-resource (workspace/resolve-resource parent-resource name)
        parent-graph-id (g/node-id->graph-id tpinfo-id)
        page-tx-data (g/make-nodes parent-graph-id [page-id [AtlasPageNode :name name :page page :file page-resource]]
                       (g/connect page-id :_node-id tpinfo-id :nodes)
                       (g/connect page-id :node-outline tpinfo-id :child-outlines)
                       (g/connect page-id :build-errors tpinfo-id :child-build-errors))
        page-id (tx-first-created page-tx-data)
        images-tx-data (create-image-nodes tpinfo-id page-id page)]
    (concat page-tx-data images-tx-data)))


(defn- create-page [parent-id page parent-resource]
  (let [page-tx (create-page-node parent-id page parent-resource)]
    page-tx))

(defn- create-pages [parent-id atlas parent-resource]
  (let [pages (.pages atlas)
        tx-data (mapcat (fn [page] (create-page parent-id page parent-resource)) pages)]
    tx-data))

; Loads the .tpinfo file (api is default ddf loader)
(defn- load-tpinfo-file [project self resource tpinfo]
  (let [path (resource/path resource)
        bytes (protobuf/map->bytes tp-plugin-tpinfo-cls tpinfo)
        atlas (plugin-create-atlas path bytes)
        page (first (.pages atlas))
        size (.size page)
        width (.width size)
        height (.height size)
        page-resources (map #(workspace/resolve-resource resource %) (.pageImageNames atlas))

        tx-data (concat
                  (g/connect project :texture-profiles self :texture-profiles)
                  (g/set-property self :tpinfo tpinfo)
                  (g/set-property self :atlas atlas)
                  (g/set-property self :width width)
                  (g/set-property self :height height)
                  (g/set-property self :frame-ids (.frameIds atlas))
                  (g/set-property self :pages (.pages atlas))
                  (g/set-property self :layouts (.layouts atlas))
                  (g/set-property self :animations (.animations atlas))
                  (g/set-property self :page-names (.pageImageNames atlas))
                  (g/set-property self :page-resources page-resources))

        all-tx-data (concat tx-data (create-pages self atlas resource))]
    all-tx-data))

(set! *warn-on-reflection* true)

;
;(defn- prop-resource-error [nil-severity _node-id prop-kw prop-value prop-name]
;  (validation/prop-error :fatal _node-id prop-kw validation/prop-resource-not-exists? prop-value prop-name))

(defn- validate-tpinfo-file [_node-id resource]
  ;; TODO: verify that the page images exist?
  (or (validation/prop-error :fatal _node-id :scene validation/prop-nil? resource "File")
      (validation/prop-error :fatal _node-id :scene validation/prop-resource-not-exists? resource "File")))

(defn- renderable->handle [renderable]
  (get-in renderable [:user-data :rive-file-handle]))

(defn- renderable->texture-set-pb [renderable]
  (get-in renderable [:user-data :texture-set-pb]))

;; *******************************************************************************************************************

;; Attaches an AtlasAnimationImage node to an AtlasAnimation node
(defn- attach-image-to-animation [animation-node image-node]
  (concat
    (g/connect image-node :_node-id animation-node :nodes)
    (g/connect image-node :build-errors animation-node :child-build-errors)
    (g/connect image-node :ddf-message animation-node :img-ddf)
    (g/connect image-node :node-outline animation-node :child-outlines)
    ;(g/connect image-node :scene animation-node :child-scenes)
    (g/connect animation-node :child->order image-node :child->order)
    (g/connect animation-node :image-names image-node :image-names)
    (g/connect animation-node :updatable image-node :animation-updatable)
    (g/connect animation-node :rename-patterns image-node :rename-patterns)))


; Attaches an AtlasAnimation to a TPAtlasNode
(defn- attach-animation-to-atlas [atlas-node animation-node]
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

;; *******************************************************************************************************************
;; AtlasAnimation

(defn- sort-by-order-and-get-image [images]
  (->> images
       (sort-by :order)
       (map #(:image %))))

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
  (texture-set/render-animation-overlay gl render-args renderables n ->texture-vtx2 atlas-shader))

(g/defnk produce-animation-updatable [_node-id id anim-data]
  (texture-set/make-animation-updatable _node-id "Atlas Animation" (get anim-data id)))

(g/defnk produce-animation-scene [_node-id id child-scenes gpu-texture updatable anim-data]
  (prn "MAWE produce-animation-scene" id updatable)
  {:node-id    _node-id
   :aabb       geom/null-aabb
   :renderable {:render-fn render-animation
                :tags #{:atlas}
                :batch-key nil
                :user-data {:gpu-texture gpu-texture
                            :anim-id     id
                            :anim-data   (get anim-data id)}
                :passes    [pass/overlay pass/selection]}
   :updatable  updatable
   :children   child-scenes})

; Structure that holds all information for an animation with multiple frames
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

  ; A map from id to id frequency (to detect duplicate names)
  (input id-counts NameCounts)
  ; A list of the static frame ids from the texture packer
  (input frame-ids g/Any)
  ; A map from source image name to the node id of the single frame AnimationImage nodes
  (input name-to-image-map g/Any)

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
             :children (sort-by :order child-outlines)
             :icon animation-icon
             :outline-error? (g/error-fatal? own-build-errors)
             :child-reqs [{:node-type AtlasAnimationImage
                           :tx-attach-fn attach-image-to-animation}]}))

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

(defn- make-atlas-animation [atlas-node anim]
  (let [graph-id (g/node-id->graph-id atlas-node)
        image-names (:images anim)]
    (g/make-nodes
      graph-id
      [atlas-anim [AtlasAnimation :flip-horizontal (:flip-horizontal anim) :flip-vertical (:flip-vertical anim)
                   :fps (:fps anim) :playback (:playback anim) :id (:id anim)]]
      (concat
        (attach-animation-to-atlas atlas-node atlas-anim)
        (make-image-nodes-in-animation atlas-anim image-names)))))

; .tpatlas file
(defn load-tpatlas-file [project self resource tpatlas]
  (let [tpinfo-resource (workspace/resolve-resource resource (:file tpatlas))
        animations (map (partial update-int->bool [:flip-horizontal :flip-vertical]) (:animations tpatlas))
        tx-data (concat
                  (g/connect project :build-settings self :build-settings)
                  (g/connect project :texture-profiles self :texture-profiles)
                  (g/set-property self
                    :file tpinfo-resource
                    :tpatlas tpatlas
                    :rename-patterns (:rename-patterns tpatlas)
                    :is-paged-atlas (:is-paged-atlas tpatlas))
                  (map (fn [animation] (make-atlas-animation self animation)) animations))]
    tx-data))


; saving the .tpatlas file
(g/defnk produce-tpatlas-save-value [file anim-ddf rename-patterns is-paged-atlas]
  (cond-> {:file (resource/resource->proj-path file)
           :rename-patterns rename-patterns
           :is-paged-atlas is-paged-atlas
           :animations anim-ddf
           }))

(defn- validate-rename-patterns [node-id rename-patterns]
  (try
    (AtlasUtil/validatePatterns rename-patterns)
    (catch Exception error
      (validation/prop-error :fatal node-id :rename-patterns identity (.getMessage error)))))

(g/defnk produce-tpatlas-own-build-errors [_node-id file rename-patterns]
  (g/package-errors _node-id
                    ; TODO: Make sure we verify the animation image names
                    (validate-rename-patterns _node-id rename-patterns)
                    (validate-tpinfo-file _node-id file)))

(defn- is-atlas-paged [tpinfo paged-atlas]
  (if (nil? tpinfo)
    false
    (if (> (count (:pages tpinfo)) 1)
      true
      paged-atlas)))

(defn- has-multi-pages [tpinfo]
  (if (nil? tpinfo)
    false
    (> (count (:pages tpinfo)) 1)))

(defn- build-array-texture [resource _dep-resources user-data]
  (let [{:keys [node-id paged-atlas atlas page-resources texture-profile]} user-data]
    (g/precluding-errors
      []
      (let [path (resource/path resource)
            buffered-images (mapv #(resource-io/with-error-translation % node-id nil
                                     (image-util/read-image %))
                                  page-resources)
            buffered-images (into-array BufferedImage buffered-images)]
        {:resource resource
         :content (protobuf/pb->bytes (plugin-create-texture path paged-atlas atlas buffered-images texture-profile))}))))

(defn make-array-texture-build-target
  [workspace node-id paged-atlas atlas texture-page-count tpinfo-page-resources tpinfo-page-resources-sha1 texture-profile]
  (let [texture-type (workspace/get-resource-type workspace "texture")
        texture-profile-pb (protobuf/pb->map texture-profile)
        texture-hash (digestable/sha1-hash
                       {:pages-sha1 tpinfo-page-resources-sha1
                        :texture-profile texture-profile-pb
                        :texture-page-count texture-page-count
                        :paged-atlas paged-atlas})
        texture-resource (resource/make-memory-resource workspace texture-type texture-hash)]
    {:node-id node-id
     :resource (workspace/make-build-resource texture-resource)
     :build-fn build-array-texture
     :content-hash texture-hash
     :user-data {:node-id node-id
                 :page-resources tpinfo-page-resources
                 :texture-profile texture-profile
                 :texture-page-count texture-page-count
                 :paged-atlas paged-atlas
                 :atlas atlas}}))


(g/defnk produce-tpatlas-build-targets [_node-id resource build-errors tpinfo texture-page-count is-paged-atlas atlas texture-set tpinfo-page-resources tpinfo-page-resources-sha1 texture-profile build-settings]
  (g/precluding-errors build-errors
    (let [project (project/get-project _node-id)
          workspace (project/workspace project)

          tex-profile (if (:compress-textures? build-settings false)
                        texture-profile
                        nil)

          use-paged-texture (or (has-multi-pages tpinfo) is-paged-atlas)

          texture-resource (make-array-texture-build-target workspace _node-id use-paged-texture atlas texture-page-count tpinfo-page-resources tpinfo-page-resources-sha1 tex-profile)

          pb-msg (protobuf/pb->map texture-set)
          dep-build-targets [texture-resource]]
      [(pipeline/make-protobuf-build-target resource dep-build-targets
                                            TextureSetProto$TextureSet
                                            (assoc pb-msg :texture (-> texture-resource :resource :resource))
                                            [:texture])])))

(g/defnk produce-anim-data [texture-set uv-transforms]
  (let [texture-set-pb (protobuf/pb->map texture-set)
        uv-transforms (into [] uv-transforms)]
    (texture-set/make-anim-data texture-set-pb uv-transforms)))

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

(g/defnk produce-tpinfo-page-resources-sha1 [_node-id tpinfo-page-resources]
  (let [flat-image-resources (filterv some? (flatten tpinfo-page-resources))
        image-sha1s (map (fn [resource]
                           (resource-io/with-error-translation resource _node-id nil
                             (resource/resource->path-inclusive-sha1-hex resource)))
                         flat-image-resources)
        errors (filter g/error? image-sha1s)]
    (if (seq errors)
      (g/error-aggregate errors)
      (let [packed-image-sha1 (digestable/sha1-hash
                                {:image-sha1s image-sha1s})]
        packed-image-sha1))))

(set! *warn-on-reflection* false)

(g/defnk produce-full-atlas [resource save-data tpinfo]
  (let [path (resource/path resource)
        tpatlas-bytes (protobuf/map->bytes tp-plugin-tpatlas-cls (protobuf/str->map tp-plugin-tpatlas-cls (:content save-data)))
        tpinfo-bytes (protobuf/map->bytes tp-plugin-tpinfo-cls tpinfo)]
    (plugin-create-full-atlas path tpatlas-bytes tpinfo-bytes)))

(g/defnk get-uv-transforms [texture-set-result] (.right texture-set-result))
(g/defnk get-texture-set [texture-set-result] (.left texture-set-result))

(set! *warn-on-reflection* true)

(defn- modify-outline [rename-patterns outline]
  (let [modified (assoc outline :label (rename-id (:node-outline-key outline) rename-patterns))]
    modified))

; We want to reuse the node outlines from the tpinfo file, but we also
; need them to display any renamed image names
(defn- make-tpinfo-node-outline-copies [rename-patterns tpinfo-node-outline]
  (let [pages (:children tpinfo-node-outline)
        result (map (fn [x] (:children x)) pages)
        images (flatten result)]
    (map (partial modify-outline rename-patterns) images)))

(g/defnode TPAtlasNode
  (inherits resource-node/ResourceNode)

  (property file resource/Resource
            (value (gu/passthrough tpinfo-file-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :tpinfo-file-resource]
                                            [:node-outline :tpinfo-node-outline]
                                            [:tpinfo :tpinfo]
                                            [:size :tpinfo-size]
                                            [:images :tpinfo-images]
                                            [:frame-ids :tpinfo-frame-ids]
                                            [:page-resources :tpinfo-page-resources]
                                            [:scene :tpinfo-scene]
                                            [:aabb :aabb]
                                            [:gpu-texture :gpu-texture]
                                            )))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext tpinfo-file-ext}))
            (dynamic error (g/fnk [_node-id file]
                             (validation/prop-error :fatal _node-id :material validation/prop-resource-not-exists? file ".tpinfo")
                             (validate-tpinfo-file _node-id file))))

  (input tpinfo-file-resource resource/Resource)
  (input tpinfo-page-resources g/Any)                       ; A resource for each png file
  (input tpinfo-node-outline g/Any)
  (input tpinfo-scene g/Any)
  (input tpinfo-images g/Any)                               ; node id's for each AtlasAnimationImage
  (input tpinfo-frame-ids g/Any)                            ; List of static frame id's from the .tpinfo file
  (output tpinfo-frame-ids g/Any (gu/passthrough tpinfo-frame-ids))

  (input tpinfo g/Any)                                      ; map of Atlas.Info from tpinfo_ddf.proto
  (input tpinfo-size g/Any)

  (property tpatlas g/Any (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [tpinfo-size] tpinfo-size))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property rename-patterns g/Str
            (dynamic error (g/fnk [_node-id rename-patterns] (validate-rename-patterns _node-id rename-patterns))))

  ; user setting, to manually choose if an atlas with a single page should use a texture array or not
  (property is-paged-atlas g/Bool
            (dynamic visible (g/fnk [tpinfo] (not (has-multi-pages tpinfo))))
            (dynamic read-only? (g/fnk [tpinfo] (has-multi-pages tpinfo))))

  (output use-texture-array g/Bool (g/fnk [tpinfo is-paged-atlas] (or (has-multi-pages tpinfo) is-paged-atlas)))

  (output texture-page-count g/Int (g/fnk [file use-texture-array tpinfo-page-resources]
                                     (if use-texture-array
                                       (count tpinfo-page-resources)
                                       texture/non-paged-page-count)))

  (input build-settings g/Any)
  (input texture-profiles g/Any)

  (input animations Animation :array)
  (output name-to-image-map g/Any :cached produce-name-to-image-map)
  (output image-names g/Any :cached produce-image-names) ; These are a list of renamed source image names

  (output frame-ids g/Any :cached (g/fnk [tpinfo-frame-ids rename-patterns] (map (fn [id] (rename-id id rename-patterns)) tpinfo-frame-ids)))

  (output atlas g/Any :cached produce-full-atlas)           ; type Atlas from Atlas.java

  (output texture-set-result g/Any :cached (g/fnk [resource atlas]
                                             (plugin-create-texture-set-result (resource/path resource) atlas "")))

  (output uv-transforms g/Any :cached get-uv-transforms)
  (output texture-set g/Any :cached get-texture-set)

  (output anim-data g/Any :cached produce-anim-data)
  (input anim-ddf g/Any :array)                             ; Array of protobuf maps for each manually created animation
  (input animation-ids g/Str :array)                        ; List of the manually created animation ids

  (input child-scenes g/Any :array)
  (input child-build-errors g/Any :array)
  (input child-outlines g/Any :array)
  (input child-source-image-outlines g/Any :array)

  (input aabb AABB)
  (output aabb AABB (gu/passthrough aabb))

  (output texture-profile g/Any (g/fnk [texture-profiles resource]
                                  (tex-gen/match-texture-profile-pb texture-profiles (resource/proj-path resource))))

  (input gpu-texture g/Any)
  (output gpu-texture g/Any (gu/passthrough gpu-texture))

  (output anim-ids g/Any :cached (g/fnk [animation-ids tpinfo-frame-ids] (filter some? (concat animation-ids tpinfo-frame-ids))))
  (output id-counts NameCounts :cached (g/fnk [anim-ids] (frequencies anim-ids)))

  (output node-outline outline/OutlineData :cached (g/fnk [_node-id tpinfo-node-outline rename-patterns child-outlines own-build-errors]
                                                     {:node-id _node-id
                                                      :node-outline-key "Atlas"
                                                      :label "Atlas"
                                                      :outline-error? (g/error-fatal? own-build-errors)
                                                      :children (concat
                                                                  (make-tpinfo-node-outline-copies rename-patterns tpinfo-node-outline)
                                                                  child-outlines)
                                                      :icon tpatlas-icon}))

  (output tpinfo-page-resources-sha1 g/Any :cached produce-tpinfo-page-resources-sha1)

  (output save-value g/Any :cached produce-tpatlas-save-value)
  (output build-targets g/Any :cached produce-tpatlas-build-targets)
  (output updatable g/Any (g/fnk [] nil))
  (output scene g/Any :cached produce-tpatlas-scene)

  (output own-build-errors g/Any produce-tpatlas-own-build-errors)
  (output build-errors g/Any (g/fnk [_node-id child-build-errors own-build-errors]
                               (g/package-errors _node-id
                                                 child-build-errors
                                                 own-build-errors))))

;; **************************************************************************************************
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
                               (make-atlas-animation atlas-node default-animation))))
        ]
    ()
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (app-view/select app-view [animation-node])))))

(handler/defhandler :add :workbench
  (label [] "Add Animation")
  (active? [selection] (selection->atlas selection))
  (run [app-view selection] (add-animation-group-handler app-view (selection->atlas selection))))


(defn- add-images-handler [app-view parent] ; parent = new parent of images
  (let [frame-ids (g/node-value parent :frame-ids)
        frame-id-items (map (fn [t] {:text t}) frame-ids)]
    (when-some [items (seq (dialogs/make-select-list-dialog frame-id-items
                                                            (:title "Select frames")))]
      (let [op-seq (gensym)
            image-text (:text (first items))

            image-nodes (g/tx-nodes-added
                          (g/transact
                            (concat
                              (g/operation-sequence op-seq)
                              (g/operation-label "Add Images")
                              (cond
                                ; Since the atlas is currently fixed, we only allow adding images to the AtlasAnimation
                                (g/node-instance? AtlasAnimation parent)
                                (make-image-nodes-in-animation parent [image-text])

                                :else
                                (let [parent-node-type @(g/node-type* parent)]
                                  (throw (ex-info (str "Unsupported parent type " (:name parent-node-type))
                                                  {:parent-node-type parent-node-type})))))))]
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (app-view/select app-view image-nodes)))))))

(handler/defhandler :add-from-file :workbench
  (label [] "Add Images...")
  (active? [selection] (selection->animation selection))
  (run [app-view project selection]
       (let [atlas (selection->atlas selection)]
         (when-some [parent-node (or atlas (selection->animation selection))]
           (let [workspace (project/workspace project)
                 accept-fn (if atlas
                             (constantly true))]
             (add-images-handler app-view workspace project parent-node accept-fn))))))


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

;; *******************************************************************************************************************

(require 'dev)
(defn- debug []
  (g/clear-system-cache!)
  (let [current (dev/active-resource)
        ;no (g/node-value current :node-outline)
        ;tpno (g/node-value current :tpinfo-node-outline)
        ;out (g/node-value current :frame-ids)
        ;out (g/node-value current :animations)
        ;out (g/node-value current :anim-ids)
        ;out (g/node-value current :anim-data)
        out (g/node-value current :paged-atlas)
        ]
    out))

(defn register-resource-types [workspace]
  (concat

    (resource-node/register-ddf-resource-type workspace
      :ext tpinfo-file-ext
      :label "Texture Packer Export File"
      :node-type TPInfoNode
      :load-fn load-tpinfo-file
      :icon tpinfo-icon
      :ddf-type tp-plugin-tpinfo-cls
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}})

    (resource-node/register-ddf-resource-type workspace
      :ext tpatlas-file-ext
      :build-ext "a.texturesetc"
      :label "Texture Packer Atlas"
      :node-type TPAtlasNode
      :ddf-type tp-plugin-tpatlas-cls
      :load-fn load-tpatlas-file
      :icon tpatlas-icon
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}}
      :template "/texturepacker/editor/resources/templates/template.tpatlas")
    ))

; The plugin
(defn load-plugin-texturepacker [workspace]
  (g/transact (concat (register-resource-types workspace)
                      (workspace/register-resource-kind-extension workspace :atlas "tpatlas"))))

(defn return-plugin []
  (fn [x] (load-plugin-texturepacker x)))
(return-plugin)
