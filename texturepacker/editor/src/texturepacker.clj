;
; MIT License
; Copyright (c) 2021 Defold
; Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
; The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;

(ns editor.texturepacker
  (:require [clojure.java.io :as io]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [editor.build-target :as bt]
            [editor.colors :as colors]
            [editor.graph-util :as gu]
    ;; [editor.geom :as geom]
            [editor.math :as math]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
    ;; [editor.gl.texture :as texture]
            [editor.gl.vertex2 :as vtx]
            [editor.defold-project :as project]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
    ;; [editor.render :as render]
            [editor.types :as types]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [editor.workspace :as workspace]
            [editor.gl.pass :as pass]
            [editor.types :as types]
            [editor.outline :as outline]
    ;; [editor.properties :as properties]
            [editor.pipeline :as pipeline]
            [editor.pipeline.tex-gen :as tex-gen]
            [editor.texture-set :as texture-set]
            [util.murmur :as murmur]
            [schema.core :as s])
  (:import [editor.gl.shader ShaderLifecycle]
           [com.jogamp.opengl GL GL2]
           [org.apache.commons.io IOUtils]
           [java.io IOException]
           [java.nio FloatBuffer IntBuffer]
           [javax.vecmath Matrix4d Vector3d Vector4d]
           ;;[com.dynamo.texturepacker.proto Info$Atlas]
           [com.dynamo.gamesys.proto Tile$Playback Tile$SpriteTrimmingMode]
           [com.dynamo.bob.pipeline AtlasUtil ShaderUtil$Common ShaderUtil$VariantTextureArrayFallback]

           ))

(set! *warn-on-reflection* true)

(def tpinfo-icon "/texturepacker/editor/resources/icons/32/icon-tpinfo.png")
(def tpatlas-icon "/texturepacker/editor/resources/icons/32/icon-tpatlas.png")
(def animation-icon "/texturepacker/editor/resources/icons/32/icon-animation.png")
(def image-icon "/texturepacker/editor/resources/icons/32/icon-image.png")

(def tpinfo-file-ext "tpinfo")
(def tpatlas-file-ext "tpatlas")

; Plugin functions (from Atlas.java)

 ;(defn- debug-cls [^Class cls]
 ;  (doseq [m (.getMethods cls)]
 ;    (prn (.toString m))
 ;    (println "Method Name: " (.getName m) "(" (.getParameterTypes m) ")")
 ;    (println "Return Type: " (.getReturnType m) "\n")))
;; TODO: Support public variables as well

(def tp-plugin-tpinfo-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Atlas"))
(def tp-plugin-tpatlas-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Atlas$AtlasDesc"))
(def tp-plugin-cls (workspace/load-class! "com.dynamo.bob.pipeline.tp.Atlas"))

(def byte-array-cls (Class/forName "[B"))
(def string-cls (Class/forName "java.lang.String"))

(defn- plugin-invoke-static [^Class cls name types args]
  (let [method (.getMethod cls name types)]
    (.invoke method nil (into-array Object args))))

(defn- plugin-create-atlas [path tpinfo-as-bytes]
  (plugin-invoke-static tp-plugin-cls "createAtlas" (into-array Class [string-cls byte-array-cls]) [path tpinfo-as-bytes]))

(g/defnk produce-tpinfo-scene
  [_node-id atlas]
  (let [
        ;[width height] layout-size
        ;pages (group-by :page layout-rects)
        ;child-renderables (into [] (for [[page-index page-rects] pages] (produce-page-renderables aabb width height page-index page-rects gpu-texture)))
        ]
    {:info-text (format "Texture Packer Export File (.tpinfo)")
     ;:children (into child-renderables
     ;                child-scenes)
     }))

(g/defnk produce-tpatlas-scene [_node-id tpinfo]
  (let [width 0
        height 0
        num-pages 0
        ;[width height] size
        ;num-pages (count (.pages tpinfo))
        ;pages (group-by :page layout-rects)
        ;child-renderables (into [] (for [[page-index page-rects] pages] (produce-page-renderables aabb width height page-index page-rects gpu-texture)))
        ]
    {:info-text (format "Atlas (.tpatlas): %d pages %d x %d" num-pages width height)
     ;:info-text (format "Atlas (.tpatlas)")
     ;:children (into child-renderables
     ;                child-scenes)
     }))


(g/defnode TPInfoNode
  (inherits resource-node/ResourceNode)
  (inherits outline/OutlineNode)

  (output path g/Str :cached (g/fnk [resource] (resource/path resource)))

  ;(input scene-structure g/Any)
  ;(output scene-structure g/Any (gu/passthrough scene-structure))

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

  (input child-scenes g/Any :array)
  (input child-outlines g/Any :array)

  (output scene g/Any :cached produce-tpinfo-scene)

  (output node-outline outline/OutlineData (g/fnk [_node-id path child-outlines]
                                                    {:node-id _node-id
                                                    :node-outline-key path
                                                    :label path
                                                    :icon animation-icon
                                                    :children child-outlines
                                                    :read-only true})))

(set! *warn-on-reflection* false)

; See TextureSetLayer$SourceImage
(g/defnode AtlasSourceImageNode
  (inherits outline/OutlineNode)
  (property name g/Str (dynamic read-only? (g/constantly true)))
  (property image g/Any (dynamic visible (g/constantly false)))

  (property rotated g/Bool
            (value (g/fnk [image] (.rotated image)))
            (dynamic read-only? (g/constantly true)))

  (output label g/Any :cached (g/fnk [name] (format "%s" name)))

  ;(property trimmed g/Bool :cached
  ;            (g/fnk [sprite] (.trimmed sprite))
  ;            (dynamic read-only? (g/constantly true)))

    ;name: "box_fill_128"
    ;trimmed: false
    ;rotated: false
    ;is_solid: true
    ;corner_offset {
    ;  x: 0
    ;  y: 0
    ;}


  ;(output transform Matrix4d :cached produce-transform)

  (output node-outline outline/OutlineData (g/fnk [_node-id name label]
                                                  {:node-id _node-id
                                                   :node-outline-key name
                                                   :label label
                                                   :icon animation-icon
                                                   :read-only true})))

; See TextureSetLayer$Page
(g/defnode AtlasPageNode
  (inherits outline/OutlineNode)
  (property name g/Str (dynamic read-only? (g/constantly true)))
  (property page g/Any (dynamic visible (g/constantly false)))

  (output width g/Num :cached (g/fnk [page] (.width (.size page))))
  (output height g/Num :cached (g/fnk [page] (.height (.size page))))

  (property size types/Vec2
             (value (g/fnk [width height] [width height]))
             (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
             (dynamic read-only? (g/constantly true)))

  (output label g/Any :cached (g/fnk [name width height] (format "%s (%d x %d)" name (int width) (int height))))

  (input nodes g/Any :array)
  (input child-outlines g/Any :array)

  ;(output transform Matrix4d :cached produce-transform)
  ;(output bone g/Any (g/fnk [name transform child-bones]
  ;                          {:name name
  ;                           :local-transform transform
  ;                           :children child-bones}))
  (output node-outline outline/OutlineData (g/fnk [_node-id name label child-outlines]
                                                  {:node-id _node-id
                                                   :node-outline-key name
                                                   :label label
                                                   :icon tpinfo-icon
                                                   :children child-outlines
                                                   :read-only true})))

(defn- create-image-node [parent-id image]
  (let [name (.name image)
        parent-graph-id (g/node-id->graph-id parent-id)
        image-tx-data (g/make-nodes parent-graph-id [page-id [AtlasSourceImageNode :name name :image image]]
                                   (g/connect page-id :_node-id parent-id :nodes)
                                   (g/connect page-id :node-outline parent-id :child-outlines))]
    image-tx-data))

(defn- create-image-nodes [parent-id page]
  (let [images (.images page)
        tx-data (mapcat (fn [image] (create-image-node parent-id image)) images)]
    tx-data))


(defn- tx-first-created [tx-data]
  (get-in (first tx-data) [:node :_node-id]))

(defn- create-page-node [parent-id page]
  (let [name (.name page)
        parent-graph-id (g/node-id->graph-id parent-id)
        page-tx-data (g/make-nodes parent-graph-id [page-id [AtlasPageNode :name name :page page]]
                                   (g/connect page-id :_node-id parent-id :nodes)
                                   (g/connect page-id :node-outline parent-id :child-outlines)
                                   ;(g/connect page :sprites parent-id :child-sprites)
                                   )
        page-id (tx-first-created page-tx-data)
        images-tx-data (create-image-nodes page-id page)]
    (concat page-tx-data images-tx-data)))


(defn- create-page [parent-id page]
  (let [page-tx (create-page-node parent-id page)]
       page-tx))

(defn- create-pages [parent-id atlas]
  (let [pages (.pages atlas)
        tx-data (mapcat (fn [page] (create-page parent-id page)) pages)]
    tx-data))

; Loads the .tpinfo file (api is default ddf loader)
(defn- load-tpinfo-file [project self resource tpinfo]
  (prn "MAWE" "load-tpinfo-file" resource)
  (let [path (resource/path resource)
        bytes (protobuf/map->bytes tp-plugin-tpinfo-cls tpinfo)
        atlas (plugin-create-atlas path bytes)
        page (first (.pages atlas))
        size (.size page)
        width (.width size)
        height (.height size)

        tx-data (concat
                  (g/set-property self :tpinfo tpinfo)
                  (g/set-property self :atlas atlas)
                  (g/set-property self :width width)
                  (g/set-property self :height height)
                  (g/set-property self :frame-ids (.frameIds atlas))
                  (g/set-property self :pages (.pages atlas))
                  (g/set-property self :layouts (.layouts atlas))
                  (g/set-property self :animations (.animations atlas))
                  (g/set-property self :page-names (.pageImageNames atlas))
                  )

        all-tx-data (concat tx-data (create-pages self atlas))
        ]
    all-tx-data))

(set! *warn-on-reflection* true)


(defn- prop-resource-error [nil-severity _node-id prop-kw prop-value prop-name]
  (validation/prop-error :fatal _node-id prop-kw validation/prop-resource-not-exists? prop-value prop-name))

(defn- validate-tpinfo-file [_node-id resource]
       ;; TODO: verify that the page images exist
  (prop-resource-error :fatal _node-id :scene resource ".tpinfo file"))

(g/defnk produce-tpatlas-own-build-errors [_node-id file]
  (g/package-errors _node-id
                    (validate-tpinfo-file _node-id file)))

(g/defnk produce-tpatlas-build-targets
  [_node-id own-build-errors resource rive-scene-pb rive-file atlas-resource dep-build-targets]
  (g/precluding-errors own-build-errors
                       (let [dep-build-targets (flatten dep-build-targets)
                             deps-by-source (into {} (map #(let [res (:resource %)] [(:resource res) res]) dep-build-targets))
                             dep-resources (map (fn [[label resource]] [label (get deps-by-source resource)]) [[:scene rive-file] [:atlas atlas-resource]])]
                         [(bt/with-content-hash
                            {:node-id _node-id
                             :resource (workspace/make-build-resource resource)
                             :build-fn nil ;build-rive-scene
                             :user-data {:proto-msg rive-scene-pb
                                         :dep-resources dep-resources}
                             :deps dep-build-targets})])))

(g/defnk produce-tpatlas-pb [_node-id rive-file-resource]
  {:scene (resource/resource->proj-path rive-file-resource)})

(defn- renderable->handle [renderable]
  (get-in renderable [:user-data :rive-file-handle]))

(defn- renderable->texture-set-pb [renderable]
  (get-in renderable [:user-data :texture-set-pb]))


; .tpatlas file
(defn load-tpatlas-file [project self resource tpatlas]
  (let [tpinfo-resource (workspace/resolve-resource resource (:file tpatlas))
        tx-data (concat
                  (g/connect project :build-settings self :build-settings)
                  (g/connect project :texture-profiles self :texture-profiles)
                  (g/set-property self
                                  ;:tpatlas tpatlas
                                  :file tpinfo-resource
                                  ))
        ;; TODO: Add custom animations here as well
        ]
    tx-data))


; saving the .tpatlas file
(g/defnk produce-tpatlas-save-value [file]
  (cond-> {:file (resource/resource->proj-path file)}))

;; (defn- validate-layout-properties [node-id margin inner-padding extrude-borders]
;;   (when-some [errors (->> [(validate-margin node-id margin)
;;                            (validate-inner-padding node-id inner-padding)
;;                            (validate-extrude-borders node-id extrude-borders)]
;;                           (filter some?)
;;                           (not-empty))]
;;     (g/error-aggregate errors)))

;; (defn- validate-rename-patterns [node-id rename-patterns]
;;   (try
;;     (AtlasUtil/validatePatterns rename-patterns)
;;     (catch Exception error
;;       (validation/prop-error :fatal node-id :rename-patterns identity (.getMessage error)))))
;
;(g/defnk produce-build-targets [_node-id resource texture-set texture-page-count packed-page-images-generator texture-profile build-settings build-errors]
;  (g/precluding-errors build-errors
;                       (let [project           (project/get-project _node-id)
;                             workspace         (project/workspace project)
;                             compress?         (:compress-textures? build-settings false)
;                             ;texture-target    (image/make-array-texture-build-target workspace _node-id packed-page-images-generator texture-profile texture-page-count compress?)
;                             texture-target    nil
;                             pb-msg            texture-set
;                             dep-build-targets [texture-target]]
;                         [(pipeline/make-protobuf-build-target resource dep-build-targets
;                                                               TextureSetProto$TextureSet
;                                                               (assoc pb-msg :texture (-> texture-target :resource :resource))
;                                                               [:texture])])))
;
;(g/defnk produce-atlas-texture-set-pb [texture-set]
;  (let [pb-msg            texture-set
;        texture-path      "" ; We don't have it at this point, as it's generated.
;        content-pb        (protobuf/map->bytes TextureSetProto$TextureSet (assoc pb-msg :texture texture-path))]
;    content-pb))

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

(defn- get-rect-page-offset [layout-width page-index]
  (let [page-margin 32]
    (+ (* page-margin page-index) (* layout-width page-index))))

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0)
  (vec1 page_index))

(defn gen-renderable-vertex-buffer
  [width height page-index]
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

; TODO - macro of this
(def atlas-shader
  (let [transformed-shader-result (ShaderUtil$VariantTextureArrayFallback/transform pos-uv-frag ShaderUtil$Common/MAX_ARRAY_SAMPLERS)
        augmented-fragment-source (.source transformed-shader-result)
        array-sampler-names (vec (.arraySamplers transformed-shader-result))
        array-sampler-uniform-names (into {}
                                          (map (fn [item] [item (array-sampler-name->uniform-names item ShaderUtil$Common/MAX_ARRAY_SAMPLERS)])
                                               array-sampler-names))]
    (shader/make-shader ::atlas-shader pos-uv-vert augmented-fragment-source {} array-sampler-uniform-names)))

;; This is for rendering atlas texture pages!
(defn- render-atlas
  [^GL2 gl render-args [renderable] n]
  (let [{:keys [pass]} render-args]
    (condp = pass
      pass/transparent
      (let [{:keys [user-data]} renderable
            {:keys [vbuf gpu-texture]} user-data
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

(defn- produce-page-renderables
  [aabb layout-width layout-height page-index gpu-texture]
  {:aabb aabb
   :transform (get-rect-transform layout-width page-index)
   :renderable {:render-fn render-atlas
                :user-data {:gpu-texture gpu-texture
                            :vbuf        (gen-renderable-vertex-buffer layout-width layout-height page-index)}
                :tags #{:atlas}
                :passes [pass/transparent]}
   :children [{:aabb aabb
               :renderable {:render-fn render-atlas-outline
                            :tags #{:atlas :outline}
                            :passes [pass/outline]}}]})

(g/defnk produce-scene
  [_node-id aabb layout-rects layout-size gpu-texture child-scenes texture-profile]
  (let [[width height] layout-size
        pages (group-by :page layout-rects)
        ;child-renderables (into [] (for [[page-index page-rects] pages] (produce-page-renderables aabb width height page-index page-rects gpu-texture)))
        ]
    {:aabb aabb
     :info-text (format "%d x %d (%s profile)" width height (:name texture-profile))
     ;:children (into child-renderables
     ;                child-scenes)
     }))

;(defn- generate-texture-set-data [{:keys [_node-id atlas]}]
;  (AtlasBuilder.createTextureSet atlas)
;  ;; (try
;  ;;   (texture-set-gen/atlas->texture-set-data animations all-atlas-images margin inner-padding extrude-borders max-page-size)
;  ;;   (catch Exception error
;  ;;     (g/->error _node-id :max-page-size :fatal nil (.getMessage error))))
;  )

;(defn- generate-texture-data [{:keys [_node-id animations texture-image-type texture-profile compress]}]
;  (try
;    (TextureUtil.createMultiPageTexture (textureImages texture-image-type texture-profile compress))
;    ;;(texture-set-gen/atlas->texture-set-data animations all-atlas-images margin inner-padding extrude-borders max-page-size)
;    (catch Exception error
;      (g/->error _node-id :max-page-size :fatal nil (.getMessage error)))))

;; (defn- call-generator [generator]
;;   ((:f generator) (:args generator)))

;; (defn- generate-packed-page-images [{:keys [_node-id image-resources layout-data-generator]}]
;;   (let [buffered-images (mapv #(resource-io/with-error-translation % _node-id nil
;;                                  (image-util/read-image %))
;;                               image-resources)]
;;     (g/precluding-errors buffered-images
;;                          (let [layout-data (call-generator layout-data-generator)]
;;                            (g/precluding-errors layout-data
;;                                                 (let [id->image (zipmap (map resource/proj-path image-resources) buffered-images)]
;;                                                   (texture-set-gen/layout-atlas-pages (:layout layout-data) id->image)))))))

;; (g/defnk produce-layout-data-generator
;;   [_node-id animation-images all-atlas-images extrude-borders inner-padding margin max-page-size :as args]
;;   ;; The TextureSetGenerator.calculateLayout() method inherited from Bob also
;;   ;; compiles a TextureSetProto$TextureSet including the animation data in
;;   ;; addition to generating the layout. This means that modifying a property on
;;   ;; an animation will unnecessarily invalidate the layout, which in turn
;;   ;; invalidates the packed image texture. For the editor, we're only interested
;;   ;; in the layout-related properties of the produced TextureSetResult. To break
;;   ;; the dependency on animation properties, we supply a list of fake animations
;;   ;; to the TextureSetGenerator.calculateLayout() method that only includes data
;;   ;; that can affect the layout.
;;   (or (validate-layout-properties _node-id margin inner-padding extrude-borders)
;;       (let [fake-animations (map make-animation
;;                                  (repeat "")
;;                                  animation-images)
;;             augmented-args (-> args
;;                                (dissoc :animation-images)
;;                                (assoc :animations fake-animations))]
;;         {:f generate-texture-set-data
;;          :args augmented-args})))

;; (g/defnk produce-packed-page-images-generator
;;   [_node-id extrude-borders image-resources inner-padding margin layout-data-generator]
;;   (let [flat-image-resources (filterv some? (flatten image-resources))
;;         image-sha1s (map (fn [resource]
;;                            (resource-io/with-error-translation resource _node-id nil
;;                              (resource/resource->path-inclusive-sha1-hex resource)))
;;                          flat-image-resources)
;;         errors (filter g/error? image-sha1s)]
;;     (if (seq errors)
;;       (g/error-aggregate errors)
;;       (let [packed-image-sha1 (digestable/sha1-hash
;;                                {:extrude-borders extrude-borders
;;                                 :image-sha1s image-sha1s
;;                                 :inner-padding inner-padding
;;                                 :margin margin
;;                                 :type :packed-atlas-image})]
;;         {:f generate-packed-page-images
;;          :sha1 packed-image-sha1
;;          :args {:_node-id _node-id
;;                 :image-resources flat-image-resources
;;                 :layout-data-generator layout-data-generator}}))))

(defn- complete-ddf-animation [ddf-animation {:keys [flip-horizontal flip-vertical fps id playback] :as _animation}]
  (assert (boolean? flip-horizontal))
  (assert (boolean? flip-vertical))
  (assert (integer? fps))
  (assert (string? id))
  (assert (protobuf/val->pb-enum Tile$Playback playback))
  (assoc ddf-animation
         :flip-horizontal (if flip-horizontal 1 0)
         :flip-vertical (if flip-vertical 1 0)
         :fps (int fps)
         :id id
         :playback playback))

(g/defnk produce-texture-set-data
  ;; The TextureSetResult we generated in produce-layout-data-generator does not
  ;; contain the animation metadata since it was produced from fake animations.
  ;; In order to produce a valid TextureSetResult, we complete the protobuf
  ;; animations inside the embedded TextureSet with our animation properties.
  [animations layout-data]
  (let [incomplete-ddf-texture-set (:texture-set layout-data)
        incomplete-ddf-animations (:animations incomplete-ddf-texture-set)
        animation-present-in-ddf? (comp not-empty :images)
        animations-in-ddf (filter animation-present-in-ddf?
                                  animations)
        complete-ddf-animations (map complete-ddf-animation
                                     incomplete-ddf-animations
                                     animations-in-ddf)
        complete-ddf-texture-set (assoc incomplete-ddf-texture-set
                                        :animations complete-ddf-animations)]
    (assoc layout-data
           :texture-set complete-ddf-texture-set)))

(g/defnk produce-anim-data
  [texture-set uv-transforms]
  (texture-set/make-anim-data texture-set uv-transforms))

(s/defrecord AtlasRect
             [path     :- s/Any
              x        :- types/Int32
              y        :- types/Int32
              width    :- types/Int32
              height   :- types/Int32
              page     :- types/Int32])

(g/defnk produce-image-path->rect
  [layout-size layout-rects]
  (let [[w h] layout-size]
    (into {} (map (fn [{:keys [path x y width height page]}]
                    [path (->AtlasRect path x (- h height y) width height page)]))
          layout-rects)))

(defn- atlas-outline-sort-by-fn [v]
  [(:name (g/node-type* (:node-id v)))])

 (g/defnode TPAtlasNode
   (inherits resource-node/ResourceNode)

   (property file resource/Resource
             (value (gu/passthrough tpinfo-file-resource))
             (set (fn [evaluation-context self old-value new-value]
                    (project/resource-setter evaluation-context self old-value new-value
                                             [:resource :tpinfo-file-resource]
                                             [:tpinfo :tpinfo]
                                             [:atlas :atlas]
                                             [:size :size]
                                             )))
             (dynamic edit-type (g/constantly {:type resource/Resource :ext tpinfo-file-ext}))
             (dynamic error (g/fnk [_node-id file]
                                   (validate-tpinfo-file _node-id file))))

   (input tpinfo-file-resource resource/Resource)
   (input tpinfo g/Any) ; map of Atlas.Info from tpinfo_ddf.proto
   (input atlas g/Any) ; type Atlas from Atlas.java
   ;(input tpatlas g/Any)

   ;(property size types/Vec2
   ;          (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
   ;          (dynamic read-only? (g/constantly true)))

   ; (property rename-patterns g/Str
   ;           (dynamic error (g/fnk [_node-id rename-patterns] (validate-rename-patterns _node-id rename-patterns))))

   ; (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

   (input build-settings g/Any)
   (input texture-profiles g/Any)

   ;(input animations Animation :array)
   ;(input animation-ids g/Str :array)
   ;(input animation-images [Image] :array)
   ;(input img-ddf g/Any :array)
   ;(input anim-ddf g/Any :array)

   (input child-scenes g/Any :array)
   (input child-build-errors g/Any :array)
   ;(input image-resources g/Any :array)

   (output texture-profile g/Any (g/fnk [texture-profiles resource]
                                        (tex-gen/match-texture-profile texture-profiles (resource/proj-path resource))))
   ;
   ;(output all-atlas-images [Image] :cached (g/fnk [animation-images]
   ;                                                (into [] (distinct) (flatten animation-images))))
   ;
   ;(output layout-data-generator g/Any          produce-layout-data-generator) ; type: TextureSetResult
   ;(output layout-data      g/Any               :cached (g/fnk [tpinfo] (.layout TextureSetResult)))
   ;(output texture-set-data g/Any               :cached generate-texture-set-data)
   ;(output layout-size      g/Any               (g/fnk [layout-data] (:size layout-data)))
   ;(output texture-set      g/Any               (g/fnk [texture-set-data] (:texture-set texture-set-data)))
   ;(output uv-transforms    g/Any               (g/fnk [layout-data] (:uv-transforms layout-data)))
   ;(output layout-rects     g/Any               (g/fnk [layout-data] (:rects layout-data)))
   ;
   ;(output texture-page-count g/Int             (g/fnk [layout-data max-page-size]
   ;                                                    (if (every? pos? max-page-size)
   ;                                                      (count (.layouts ^TextureSetGenerator$LayoutResult (:layout layout-data)))
   ;                                                      texture/non-paged-page-count)))

   ;(output packed-page-images-generator g/Any   produce-packed-page-images-generator)
   ;
   ;(output packed-page-images [BufferedImage]   :cached (g/fnk [packed-page-images-generator] (call-generator packed-page-images-generator)))
   ;
   ;(output texture-set-pb   g/Any               :cached produce-atlas-texture-set-pb)
   ;(output texture-pb   g/Any                   :cached produce-atlas-texture-pb)
   ;
   ;(output aabb             AABB                (g/fnk [layout-size]
   ;                                                    (if (= [0 0] layout-size)
   ;                                                      geom/null-aabb
   ;                                                      (let [[w h] layout-size]
   ;                                                        (types/->AABB (Point3d. 0 0 0) (Point3d. w h 0))))))
   ;
   ;(output gpu-texture      g/Any               :cached (g/fnk [_node-id packed-page-images texture-profile]
   ;                                                            (let [page-texture-images
   ;                                                                  (mapv #(tex-gen/make-preview-texture-image % texture-profile)
   ;                                                                        packed-page-images)]
   ;                                                              (texture/texture-images->gpu-texture
   ;                                                               _node-id
   ;                                                               page-texture-images
   ;                                                               {:min-filter gl/nearest
   ;                                                                :mag-filter gl/nearest}))))
   ;
   ;(output anim-data        g/Any               :cached produce-anim-data)
   ;(output image-path->rect g/Any               :cached produce-image-path->rect)
   ;(output anim-ids         g/Any               :cached (g/fnk [animation-ids] (filter some? animation-ids)))
   ;(output id-counts        NameCounts          :cached (g/fnk [anim-ids] (frequencies anim-ids)))

   ;(output node-outline     outline/OutlineData :cached (g/fnk [_node-id child-outlines own-build-errors]
   ;                                                            {:node-id          _node-id
   ;                                                             :node-outline-key "Atlas"
   ;                                                             :label            "Atlas"
   ;                                                             :children         (vec (sort-by atlas-outline-sort-by-fn child-outlines))
   ;                                                             :icon             atlas-icon
   ;                                                             :outline-error?   (g/error-fatal? own-build-errors)
   ;                                                             :child-reqs       [{:node-type    AtlasImage
   ;                                                                                 :tx-attach-fn attach-image-to-atlas}
   ;                                                                                {:node-type    AtlasAnimation
   ;                                                                                 :tx-attach-fn attach-animation-to-atlas}]}))
   (output save-value       g/Any          :cached produce-tpatlas-save-value)
   ;(output build-targets    g/Any          :cached produce-build-targets)
   ;(output updatable        g/Any          (g/fnk [] nil))
   (output scene g/Any :cached produce-tpatlas-scene)

   ;(output own-build-errors g/Any          (g/fnk [_node-id extrude-borders inner-padding margin max-page-size rename-patterns]
   ;                                               (g/package-errors _node-id
   ;                                                                 (validate-margin _node-id margin)
   ;                                                                 (validate-inner-padding _node-id inner-padding)
   ;                                                                 (validate-extrude-borders _node-id extrude-borders)
   ;                                                                 (validate-max-page-size _node-id max-page-size)
   ;                                                                 (validate-rename-patterns _node-id rename-patterns))))
   ;(output build-errors     g/Any          (g/fnk [_node-id child-build-errors own-build-errors]
   ;                                               (g/package-errors _node-id
   ;                                                                 child-build-errors
   ;                                                                 own-build-errors)))
            )


(defn register-resource-types [workspace]
  (concat

    (resource-node/register-ddf-resource-type workspace
                                             :ext tpinfo-file-ext
                                             :label "Texture Packer Export File"
                                             :node-type TPInfoNode
                                             :load-fn load-tpinfo-file
                                             :icon tpinfo-icon
                                             :ddf-type tp-plugin-tpinfo-cls
                                             :view-types [:scene :text]))

    ;(resource-node/register-ddf-resource-type workspace
    ;                                          :ext tpatlas-file-ext
    ;                                          :build-ext "a.texturesetc"
    ;                                          :label "Texture Packer Atlas"
    ;                                          :node-type TPAtlasNode
    ;                                          :ddf-type tp-plugin-tpatlas-cls
    ;                                          :load-fn load-tpatlas-file
    ;                                          :icon tpatlas-icon
    ;                                          :view-types [:scene :text]
    ;                                          :view-opts {:scene {:grid true}}
    ;                                          :template "/texturepacker/resources/templates/template.tpatlas")
      )

; The plugin
(defn load-plugin-texturepacker [workspace]
  (g/transact (concat (register-resource-types workspace))))

(defn return-plugin []
  (fn [x] (load-plugin-texturepacker x)))
(return-plugin)
