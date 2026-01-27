;;
;; MIT License
;; Copyright (c) 2024 Defold
;; Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
;; The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;;

(ns editor.texturepacker
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.build-target :as bt]
            [editor.colors :as colors]
            [editor.defold-project :as project]
            [editor.dialogs :as dialogs]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.pass :as pass]
            [editor.gl.texture :as texture]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.localization :as localization]
            [editor.outline :as outline]
            [editor.pipeline :as pipeline]
            [editor.pipeline.tex-gen :as tex-gen]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.render-util :as render-util]
            [editor.resource :as resource]
            [editor.resource-dialog :as resource-dialog]
            [editor.resource-node :as resource-node]
            [editor.scene-picking :as scene-picking]
            [editor.texture-set :as texture-set]
            [editor.texture-util :as texture-util]
            [editor.types :as types]
            [editor.ui.fuzzy-choices :as fuzzy-choices]
            [editor.util :as util]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.java :as java]
            [schema.core :as s]
            [util.coll :refer [pair]])
  (:import [com.dynamo.bob.pipeline AtlasUtil TextureGenerator$GenerateResult]
           [com.dynamo.bob.textureset TextureSetLayout$Page TextureSetLayout$SourceImage]
           [com.dynamo.gamesys.proto Tile$Playback]
           [com.dynamo.gamesys.proto TextureSetProto$TextureSet]
           [com.dynamo.graphics.proto Graphics$TextureProfile]
           [com.jogamp.opengl GL2]
           [editor.gl.pass RenderPass]
           [editor.types AABB]
           [java.io File]
           [java.lang IllegalArgumentException]
           [java.lang.reflect Method]
           [java.util List]
           [javax.vecmath Matrix4d Point3d Vector3d]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private tpinfo-icon "/texturepacker/editor/resources/icons/32/icon-tpinfo.png")
(def ^:private tpatlas-icon "/texturepacker/editor/resources/icons/32/icon-tpatlas.png")
(def ^:private animation-icon "/texturepacker/editor/resources/icons/32/icon-animation.png")
(def ^:private image-icon "/texturepacker/editor/resources/icons/32/icon-image.png")

(def ^:private tpinfo-resource-label "Texture Packer Export File")
(def ^:private tpatlas-resource-label "Texture Packer Atlas")

(def ^:private tpinfo-file-ext "tpinfo")
(def ^:private tpatlas-file-ext "tpatlas")

;; Plugin functions (from Atlas.java)

(def ^:private tpinfo-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Atlas"))
(def ^:private tpinfo-page-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Info$Page"))
(def ^:private tpatlas-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Atlas$AtlasDesc"))
(def ^:private tpatlas-animation-pb-cls (workspace/load-class! "com.dynamo.texturepacker.proto.Atlas$AtlasAnimation"))
(def ^:private tp-plugin-cls (workspace/load-class! "com.dynamo.bob.pipeline.tp.Atlas"))

(def ^:private byte-array-cls (Class/forName "[B"))

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
  "Creates a TextureSetLayout$Page from an instance of tpinfo-page-pb-cls."
  ^TextureSetLayout$Page [^long page-index tpinfo-page-pb]
  (plugin-invoke-static tp-plugin-cls "createLayoutPage"
                        [Integer/TYPE tpinfo-page-pb-cls]
                        [(int page-index) tpinfo-page-pb]))

(defn- plugin-create-full-atlas
  "Creates an instance of tp-plugin-cls, using both tpinfo and tpatlas data.
  Used when producing the build output for TPAtlasNode. All image references are
  expected to use the original names."
  [^String path ^bytes tpatlas-as-bytes ^bytes tpinfo-as-bytes]
  (plugin-invoke-static tp-plugin-cls "createFullAtlas"
                        [String byte-array-cls byte-array-cls]
                        [path tpatlas-as-bytes tpinfo-as-bytes]))

(defn- plugin-create-texture-set-result
  "Returns a Pair<TextureSet, List<TextureSetGenerator$UVTransform>>. Use
  (.left %) and (.right %) to obtain the components of the Pair."
  [^String path atlas ^String texture-path]
  (plugin-invoke-static tp-plugin-cls "createTextureSetResult"
                        [String tp-plugin-cls String]
                        [path atlas texture-path]))

(defn- plugin-create-texture
  "Creates the final texture (TextureGenerator$GenerateResult)."
  ^TextureGenerator$GenerateResult [^String path is-paged buffered-images ^Graphics$TextureProfile texture-profile-pb compress]
  (plugin-invoke-static tp-plugin-cls "createTexture"
                        [String Boolean/TYPE List Graphics$TextureProfile Boolean/TYPE]
                        [path is-paged buffered-images texture-profile-pb compress]))

(defn- plugin-source-image-triangle-vertices
  "Returns a float array (2-tuples) that is a triangle list:
  [t0.x0, t0.y0, t0.x1, t0.y1, t0.x2, t0.y2, t1.x0, t1.y0, ...]."
  ^floats [^TextureSetLayout$SourceImage source-image page-height]
  (plugin-invoke-static tp-plugin-cls "getTriangles"
                        [TextureSetLayout$SourceImage Float]
                        [source-image page-height]))

(def ^:private TFinalName (s/named s/Str "final-name"))
(def ^:private TNodeID (s/named s/Int "node-id"))
(def ^:private TOriginalName (s/named s/Str "original-name"))
(def ^:private TPageImageName (s/named s/Str "page-image-name"))

(def ^:private TSceneRenderable
  {:passes [RenderPass]
   :render-fn (s/pred fn?)
   (s/optional-key :batch-key) s/Any
   (s/optional-key :select-batch-key) s/Any
   (s/optional-key :tags) #{s/Keyword}
   (s/optional-key :topmost?) s/Bool
   (s/optional-key :user-data) s/Any})

(def ^:private TSceneUpdatable
  {:initial-state s/Any
   :update-fn (s/pred fn?)
   (s/optional-key :name) s/Str
   (s/optional-key :node-id) TNodeID})

(def ^:private TScene
  {(s/optional-key :aabb) AABB
   (s/optional-key :children) [(s/recursive #'TScene)]
   (s/optional-key :info-text) s/Str
   (s/optional-key :node-id) TNodeID
   (s/optional-key :renderable) TSceneRenderable
   (s/optional-key :transform) Matrix4d
   (s/optional-key :updatable) (s/maybe TSceneUpdatable)})

(def ^:private TNodeID+OriginalName
  (s/pair TNodeID "node-id" TOriginalName "original-name"))

(def ^:private TPageInfo
  {:image-name TPageImageName
   :image-node-id+original-names [TNodeID+OriginalName]})

(def ^:private TSize
  (s/pair s/Num "width" s/Num "height"))

(def ^:private TPoint
  (s/pair s/Num "x" s/Num "y"))

(def ^:private TImageInfo
  {:index-count s/Int
   :pivot TPoint
   :size TSize
   :untrimmed-size TSize
   :vertex-count s/Int})

(g/deftype ^:private FinalNameCounts {TFinalName s/Int})
(g/deftype ^:private ImageInfo TImageInfo)
(g/deftype ^:private LayoutPageVec [TextureSetLayout$Page])
(g/deftype ^:private NodeID+OriginalName TNodeID+OriginalName)
(g/deftype ^:private OriginalName->ImageInfo {TOriginalName TImageInfo})
(g/deftype ^:private OriginalName->Scene {TOriginalName TScene})
(g/deftype ^:private PageImageName TPageImageName)
(g/deftype ^:private PageInfo TPageInfo)
(g/deftype ^:private Scene TScene)
(g/deftype ^:private SceneUpdatable TSceneUpdatable)
(g/deftype ^:private SceneVec [TScene])

(defn- make-page-scene [^TextureSetLayout$Page layout-page ^Matrix4d page-offset-transform gpu-texture]
  (let [layout-size (.size layout-page)
        page-index (.index layout-page)
        page-width (.width layout-size)
        page-height (.height layout-size)]
    (render-util/make-outlined-textured-quad-scene #{:atlas} page-offset-transform page-width page-height gpu-texture page-index)))

(defn- scene-info-text
  ([resource-type-label ^long page-count page-size]
   (if (zero? page-count)
     (format "%s: No pages" resource-type-label)
     (let [[page-width page-height] page-size]
       (format "%s: %d pages, %d x %d" resource-type-label page-count (long page-width) (long page-height)))))
  ([resource-label ^long page-count page-size texture-profile-name]
   (let [basic-info-text (scene-info-text resource-label page-count page-size)]
     (format "%s (%s profile)" basic-info-text (or texture-profile-name "no")))))

(g/defnk produce-tpinfo-scene [_node-id gpu-texture image-scenes-by-original-name layout-pages page-offset-transforms size]
  (let [info-text (scene-info-text tpinfo-resource-label (count layout-pages) size)
        page-scenes (mapv #(make-page-scene %1 %2 gpu-texture)
                          layout-pages
                          page-offset-transforms)]
    {:info-text info-text
     :children (into page-scenes
                     (map val)
                     image-scenes-by-original-name)}))

(g/defnk produce-tpatlas-scene [_node-id size texture-profile tpinfo tpinfo-scene animation-scenes]
  (let [info-text (scene-info-text tpatlas-resource-label (count (:pages tpinfo)) size (:name texture-profile))]
    {:info-text info-text
     :children (if (nil? tpinfo-scene)
                 []
                 (into [tpinfo-scene]
                       animation-scenes))}))

(g/defnk produce-tpinfo-save-value [page-infos tpinfo]
  ;; The user might have moved or renamed the page image files in the project.
  ;; Ensure the page image names are up-to-date with the project structure.
  (let [pages (:pages tpinfo)]
    (if (empty? pages)
      tpinfo
      (let [pages-with-up-to-date-image-names
            (mapv (fn [page page-info]
                    (assoc page :name (:image-name page-info)))
                  pages
                  page-infos)]
        (assoc tpinfo :pages pages-with-up-to-date-image-names)))))

(defn- size->vec2 [{:keys [width height]}]
  [width height])

(defn- tpinfo->size-vec2 [tpinfo]
  (some-> tpinfo :pages first :size size->vec2))

(defn- make-gpu-texture [request-id page-image-content-generators texture-profile]
  (-> (texture-util/construct-gpu-texture request-id page-image-content-generators texture-profile)
      (texture/set-params {:min-filter gl/nearest
                           :mag-filter gl/nearest})))

(defn- render-image-geometry [^GL2 gl world-positions color]
  (let [[^double cr ^double cg ^double cb ^double ca] color]
    (.glColor4d gl cr cg cb ca)
    (.glBegin gl GL2/GL_TRIANGLES)
    (doseq [[^double x ^double y ^double z] world-positions]
      (.glVertex3d gl x y z))
    (.glEnd gl)))

(defn- render-image-outline
  [^GL2 gl renderable override-color]
  (let [world-positions (-> renderable :user-data :world-positions)
        color (or override-color (colors/renderable-outline-color renderable))]
    (render-image-geometry gl world-positions color)))

(defn- render-image-outlines
  [^GL2 gl render-args renderables _renderable-count]
  (assert (= (:pass render-args) pass/outline))
  (let [{:keys [default playing selected]}
        (group-by (fn [{:keys [selected updatable user-data]}]
                    (cond
                      (= :self-selected selected)
                      :selected

                      (and updatable
                           (= (:frame user-data)
                              (-> updatable :state :frame)))
                      :playing

                      :else
                      :default))
                  renderables)]
    (doseq [renderable default]
      (render-image-outline gl renderable nil))
    (doseq [renderable playing]
      (render-image-outline gl renderable colors/defold-pink))
    (doseq [renderable selected]
      (render-image-outline gl renderable nil))))

(defn- render-image-selection
  [^GL2 gl render-args renderables renderable-count]
  (assert (= (:pass render-args) pass/selection))
  (assert (= renderable-count 1))
  (let [renderable (first renderables)
        picking-id (:picking-id renderable)
        id-color (scene-picking/picking-id->color picking-id)
        world-positions (-> renderable :user-data :world-positions)]
    (render-image-geometry gl world-positions id-color)))

(defn- point->vec3 [^Point3d point]
  (vector-of :double (.x point) (.y point) (.z point)))

(defn- make-image-scene [image-node-id ^TextureSetLayout$SourceImage source-image ^TextureSetLayout$Page layout-page page-offset-transforms]
  (let [page-size (.size layout-page)
        page-height (.height page-size)
        page-index (.index layout-page)
        ^Matrix4d page-offset-transform (page-offset-transforms page-index)
        interleaved-xys (plugin-source-image-triangle-vertices source-image page-height)

        ;; We calculate the AABB from the vertex positions because the rect of
        ;; the SourceImage includes the empty space that was around the image
        ;; before it was trimmed away and composed into an atlas. For our
        ;; purposes, we want the AABB to encompass the vertices of the trimmed
        ;; hull, so that it can be framed and box-selected in the scene view.
        [world-aabb world-positions]
        (let [length (alength interleaved-xys)
              point (Point3d.)]
          (loop [index 0
                 world-aabb geom/null-aabb
                 world-positions (transient [])]
            (if (< index length)
              (let [x (aget interleaved-xys index)
                    y (aget interleaved-xys (inc index))]
                (.set point x y 0.0)
                (.transform page-offset-transform point)
                (recur (+ index 2)
                       (geom/aabb-incorporate world-aabb point)
                       (conj! world-positions (point->vec3 point))))
              (pair world-aabb (persistent! world-positions)))))]

    ;; The scenes are not parented to the transformed atlas pages. Instead, they
    ;; are expressed in world-space so that they can be used as immediate child
    ;; scenes to the untransformed TPInfoNode or AtlasAnimationNode scenes.
    {:node-id image-node-id
     :aabb world-aabb
     :renderable {:render-fn render-image-outlines
                  :tags #{:atlas :outline}
                  :batch-key ::atlas-image-outline
                  :user-data {:world-positions world-positions}
                  :passes [pass/outline]}
     :children [{:aabb world-aabb
                 :node-id image-node-id
                 :renderable {:render-fn render-image-selection
                              :tags #{:atlas}
                              :user-data {:world-positions world-positions}
                              :passes [pass/selection]}}]}))

(g/defnode TPInfoNode
  (inherits resource-node/ResourceNode)
  (inherits outline/OutlineNode)

  (property tpinfo g/Any (dynamic visible (g/constantly false))) ; Loaded tpinfo. Use save-value instead when you need up-to-date resource paths.
  (property layout-pages LayoutPageVec (dynamic visible (g/constantly false)))

  (property size types/Vec2
            (value (g/fnk [tpinfo] (tpinfo->size-vec2 tpinfo)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property version g/Str
            (value (g/fnk [tpinfo] (:version tpinfo)))
            (dynamic read-only? (g/constantly true)))

  (property description g/Str
            (value (g/fnk [tpinfo] (:description tpinfo)))
            (dynamic read-only? (g/constantly true)))

  (input page-infos PageInfo :array)
  (input page-build-errors g/Any :array)

  (input page-image-content-generators g/Any :array)
  (output page-image-content-generators g/Any (gu/passthrough page-image-content-generators))

  (output image-infos-by-original-name OriginalName->ImageInfo :cached
          (g/fnk [tpinfo]
            (into {}
                  (mapcat
                    (fn [page]
                      (map (fn [{:keys [frame-rect indices untrimmed-size vertices pivot] :as sprite}]
                             (let [original-name (:name sprite)
                                   size [(:width frame-rect) (:height frame-rect)]
                                   width (double (:width untrimmed-size))
                                   height (double (:height untrimmed-size))
                                   untrimmed-size [width height]
                                   pivot (if pivot
                                          [(:x pivot) (:y pivot)]
                                          [(/ width 2.0) (/ height 2.0)])]
                               (pair original-name
                                     {:pivot pivot
                                      :size size
                                      :untrimmed-size untrimmed-size
                                      :vertex-count (count vertices)
                                      :index-count (count indices)})))
                           (:sprites page))))
                  (:pages tpinfo))))

  (output page-offset-transforms [Matrix4d] :cached
          (g/fnk [layout-pages]
            (if (zero? (count layout-pages))
              []
              (->> layout-pages
                   (pop)
                   (reductions (fn [^double prev-page-offset ^TextureSetLayout$Page layout-page]
                                 (let [layout-size (.size layout-page)
                                       page-width (.width layout-size)
                                       page-spacing 32.0]
                                   (+ prev-page-offset page-spacing page-width)))
                               0.0)
                   (mapv (fn [^double page-offset-x]
                           (doto (Matrix4d.)
                             (.setIdentity)
                             (.setTranslation (Vector3d. page-offset-x 0.0 0.0)))))))))

  (output image-scenes-by-original-name OriginalName->Scene :cached
          (g/fnk [layout-pages page-infos page-offset-transforms]
            (let [original-name->image-node-id
                  (into {}
                        (comp (mapcat :image-node-id+original-names)
                              (map (fn [[image-node-id original-name]]
                                     (pair original-name image-node-id))))
                        page-infos)]

              (into {}
                    (mapcat
                      (fn [^TextureSetLayout$Page layout-page]
                        (map (fn [^TextureSetLayout$SourceImage source-image]
                               (let [original-name (.name source-image)
                                     image-node-id (original-name->image-node-id original-name)
                                     scene (make-image-scene image-node-id source-image layout-page page-offset-transforms)]
                                 (pair original-name scene)))
                             (.images layout-page))))
                    layout-pages))))

  (output parent-dir-file File :cached
          (g/fnk [resource]
            ;; This is used to convert page image proj-paths to "page names" relative to the `.tpinfo` file.
            (-> resource
                (resource/proj-path) ; proj-path works with zip resources, and we're only interested in the path here.
                (io/file)
                (.getParentFile))))

  (output gpu-texture g/Any :cached
          (g/fnk [_node-id page-image-content-generators]
            (make-gpu-texture _node-id page-image-content-generators nil)))

  (output scene Scene :cached produce-tpinfo-scene)

  (output build-errors g/Any
          (g/fnk [_node-id page-build-errors]
            (g/package-errors _node-id
                              page-build-errors)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id child-outlines build-errors]
            {:node-id _node-id
             :node-outline-key tpinfo-resource-label
             :label tpinfo-resource-label
             :icon tpinfo-icon
             :read-only true
             :outline-error? (g/error-fatal? build-errors)
             :children child-outlines}))

  (output save-value g/Any :cached produce-tpinfo-save-value))

(defn- rename-id [id rename-patterns]
  (if rename-patterns
    (try (AtlasUtil/replaceStrings rename-patterns id) (catch Exception _ id))
    id))

(defn- original-name-missing-in-tpinfo? [original-name tpinfo-image-infos-by-original-name]
  (when-not (contains? tpinfo-image-infos-by-original-name original-name)
    (format "Image '%s' does not exist in the .tpinfo file referenced by the atlas." original-name)))

(defn- validate-original-name [node-id original-name tpinfo-image-infos-by-original-name]
  (validation/prop-error :fatal node-id :original-name original-name-missing-in-tpinfo? original-name tpinfo-image-infos-by-original-name))

(declare AtlasPageNode)

(defn- image-owned-by-tpinfo? [image-node-id]
  (g/node-instance? AtlasPageNode
                    (g/node-feeding-into image-node-id
                                         :tpinfo-image-infos-by-original-name)))

(g/defnode AtlasImageNode
  (inherits outline/OutlineNode)

  ;; The original-name is the only information in the AtlasImageNode. It is used
  ;; to look up the ImageInfo associated with this image in the `.tpinfo` file.
  ;; The `.tpinfo` file is always the source of truth, we just present
  ;; information from it. This way, an AtlasImageNode can be copied from a
  ;; TPInfoNode to an AtlasAnimationNode, moved between AtlasAnimationNodes, or
  ;; edited to address a different image in the `.tpinfo` file, while always
  ;; presenting up-to-date information from the `.tpinfo` file.
  (property original-name g/Str
            (dynamic label (g/constantly "Image"))
            (dynamic read-only? (g/fnk [_node-id]
                                  (image-owned-by-tpinfo? _node-id)))
            (dynamic edit-type (g/fnk [tpinfo-image-infos-by-original-name]
                                 (properties/->choicebox (keys tpinfo-image-infos-by-original-name))))
            (dynamic error (g/fnk [_node-id original-name tpinfo-image-infos-by-original-name]
                             (validate-original-name _node-id original-name tpinfo-image-infos-by-original-name))))

  (property pivot types/Vec2
            (value (g/fnk [tpinfo-image-info] (:pivot tpinfo-image-info)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["X" "Y"]}))
            (dynamic read-only? (g/constantly true)))

  (property size types/Vec2
            (value (g/fnk [tpinfo-image-info] (:size tpinfo-image-info)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property untrimmed-size types/Vec2
            (value (g/fnk [tpinfo-image-info] (:untrimmed-size tpinfo-image-info)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property vertex-count g/Int
            (value (g/fnk [tpinfo-image-info] (:vertex-count tpinfo-image-info)))
            (dynamic read-only? (g/constantly true)))

  (property index-count g/Int
            (value (g/fnk [tpinfo-image-info] (:index-count tpinfo-image-info)))
            (dynamic read-only? (g/constantly true)))

  (input tpinfo-image-infos-by-original-name OriginalName->ImageInfo)

  (output tpinfo-image-info ImageInfo ; For internal use only.
          (g/fnk [tpinfo-image-infos-by-original-name original-name]
            (get tpinfo-image-infos-by-original-name original-name)))

  (output node-id+original-name NodeID+OriginalName
          (g/fnk [_node-id original-name]
            (pair _node-id original-name)))

  (output node-outline outline/OutlineData (g/constantly nil)))

(defn- validate-page-image [page-node-id page-image-resource]
  (or (validation/prop-error :fatal page-node-id :image validation/prop-empty? page-image-resource "Image")
      (validation/prop-error :fatal page-node-id :image validation/prop-resource-not-exists? page-image-resource "Image")))

;; See TextureSetLayer$Page
(g/defnode AtlasPageNode
  (inherits outline/OutlineNode)

  (property layout-page TextureSetLayout$Page (dynamic visible (g/constantly false)))

  (property image resource/Resource
            (value (gu/passthrough image-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :image-resource]
                                            [:content-generator :image-content-generator])))
            (dynamic error (g/fnk [_node-id image-resource]
                             (validate-page-image _node-id image-resource))))

  (property size types/Vec2
            (value (g/fnk [^TextureSetLayout$Page layout-page]
                     (let [layout-size (.size layout-page)]
                       [(.width layout-size) (.height layout-size)])))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (input image-node-id+original-names NodeID+OriginalName :array :cascade-delete)
  (input tpinfo-parent-dir-file File) ; Used to convert the page image resource proj-path into a page image file name relative to the `.tpinfo` file.
  (input image-resource resource/Resource)
  (input image-content-generator g/Any)

  (input tpinfo-image-infos-by-original-name OriginalName->ImageInfo)
  (output tpinfo-image-infos-by-original-name OriginalName->ImageInfo (gu/passthrough tpinfo-image-infos-by-original-name))

  (output image-content-generator g/Any
          (g/fnk [_node-id image-content-generator image-resource]
            (or (validate-page-image _node-id image-resource)
                image-content-generator)))

  (output image-name PageImageName :cached
          (g/fnk [image-resource tpinfo-parent-dir-file]
            (if (nil? image-resource)
              ""
              (let [image-proj-path (resource/proj-path image-resource)
                    image-file (io/file image-proj-path)]
                (resource/relative-path tpinfo-parent-dir-file image-file)))))

  (output page-info PageInfo :cached
          (g/fnk [image-name image-node-id+original-names]
            {:image-name image-name
             :image-node-id+original-names (vec image-node-id+original-names)}))

  (output build-errors g/Any
          (g/fnk [_node-id image-resource]
            (g/package-errors _node-id
                              (validate-page-image _node-id image-resource))))

  (output image-outlines g/Any :cached
          (g/fnk [image-node-id+original-names]
            (mapv (fn [[node-id original-name]]
                    {:node-id node-id
                     :node-outline-key original-name
                     :label original-name
                     :icon image-icon
                     :read-only true})
                  image-node-id+original-names)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id image-name image-outlines size build-errors]
            (let [[width height] size
                  label (format "%s (%d x %d)" image-name (int width) (int height))]
              {:node-id _node-id
               :node-outline-key image-name
               :label label
               :icon tpatlas-icon
               :read-only true
               :outline-error? (g/error-fatal? build-errors)
               :children image-outlines}))))

(defn- add-image-node-to-page-node [page-node ^TextureSetLayout$SourceImage source-image]
  (let [original-name (.name source-image)
        graph-id (g/node-id->graph-id page-node)]
    (g/make-nodes graph-id [image-node [AtlasImageNode :original-name original-name]]
      (g/connect page-node :tpinfo-image-infos-by-original-name image-node :tpinfo-image-infos-by-original-name)
      (g/connect image-node :node-id+original-name page-node :image-node-id+original-names))))

(defn- add-page-node-to-tpinfo-node [tpinfo-node page-image-resource ^TextureSetLayout$Page layout-page]
  (let [graph-id (g/node-id->graph-id tpinfo-node)]
    (g/make-nodes graph-id [page-node [AtlasPageNode :layout-page layout-page :image page-image-resource]]
      (g/connect tpinfo-node :parent-dir-file page-node :tpinfo-parent-dir-file)
      (g/connect tpinfo-node :image-infos-by-original-name page-node :tpinfo-image-infos-by-original-name)
      (g/connect page-node :_node-id tpinfo-node :nodes)
      (g/connect page-node :node-outline tpinfo-node :child-outlines)
      (g/connect page-node :page-info tpinfo-node :page-infos)
      (g/connect page-node :image-content-generator tpinfo-node :page-image-content-generators)
      (g/connect page-node :build-errors tpinfo-node :page-build-errors)
      (for [source-image (.images layout-page)]
        (add-image-node-to-page-node page-node source-image)))))

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
              pages)]

    (concat
      (g/set-property self :tpinfo tpinfo)
      (g/set-property self :layout-pages layout-pages)
      (mapcat
        (fn [page-image-resource layout-page]
          (add-page-node-to-tpinfo-node self page-image-resource layout-page))
        page-image-resources
        layout-pages))))

(defn- validate-tpinfo-file [_node-id resource]
  (or (validation/prop-error :fatal _node-id :file validation/prop-nil? resource "File")
      (validation/prop-error :fatal _node-id :file validation/prop-resource-not-exists? resource "File")))

(defn- validate-tpinfo-for-tpatlas-use [_node-id tpinfo]
  (validation/prop-error :fatal _node-id :file
                         (fn [^long page-count]
                           (when-not (pos? page-count)
                             "Referenced .tpinfo file contains no images."))
                         (count (:pages tpinfo))))

;; *****************************************************************************

;; Attaches an AtlasImageNode to an AtlasAnimationNode
(defn- attach-image-to-animation [animation-node image-node]
  (concat
    (g/connect animation-node :tpinfo-image-infos-by-original-name image-node :tpinfo-image-infos-by-original-name)
    (g/connect image-node :node-id+original-name animation-node :image-node-id+original-names)))

;; Attaches an AtlasAnimationNode to a TPAtlasNode
(defn- attach-animation-to-atlas [atlas-node animation-node]
  (concat
    (g/connect atlas-node :tpinfo-image-infos-by-original-name animation-node :tpinfo-image-infos-by-original-name)
    (g/connect atlas-node :tpinfo-image-scenes-by-original-name animation-node :tpinfo-image-scenes-by-original-name)
    (g/connect atlas-node :id-counts animation-node :id-counts)
    (g/connect atlas-node :rename-patterns animation-node :rename-patterns)
    (g/connect atlas-node :gpu-texture animation-node :gpu-texture)
    (g/connect atlas-node :anim-data animation-node :anim-data)
    (g/connect animation-node :_node-id atlas-node :nodes)
    (g/connect animation-node :node-outline atlas-node :child-outlines)
    (g/connect animation-node :scene atlas-node :animation-scenes)
    (g/connect animation-node :save-value atlas-node :animation-save-values)
    (g/connect animation-node :build-errors atlas-node :animation-build-errors)
    (g/connect animation-node :id atlas-node :animation-ids)))

;; *****************************************************************************
;; AtlasAnimationNode

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

(g/defnk produce-animation-save-value [id fps flip-horizontal flip-vertical playback image-node-id+original-names]
  (protobuf/make-map-without-defaults tpatlas-animation-pb-cls
    :id id
    :fps fps
    :flip-horizontal (protobuf/boolean->int flip-horizontal)
    :flip-vertical (protobuf/boolean->int flip-vertical)
    :playback playback
    :images (mapv (fn [[_node-id original-name]]
                    original-name)
                  image-node-id+original-names)))

(defn- render-animation [^GL2 gl render-args renderables _renderable-count]
  (texture-set/render-animation-overlay gl render-args renderables))

(g/defnk produce-animation-updatable [_node-id id anim-data]
  (texture-set/make-animation-updatable _node-id "Atlas Animation" (get anim-data id)))

(g/defnk produce-animation-scene [_node-id id image-scenes gpu-texture updatable anim-data]
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
   :children image-scenes})

;; Structure that holds all information for an animation with multiple frames
(g/defnode AtlasAnimationNode
  (inherits outline/OutlineNode)

  (property id g/Str
            (dynamic error (g/fnk [_node-id id id-counts] (validate-animation-id _node-id id id-counts))))
  (property fps g/Int (default (protobuf/default tpatlas-animation-pb-cls :fps))
            (dynamic error (g/fnk [_node-id fps] (validate-animation-fps _node-id fps))))
  (property flip-horizontal g/Bool (default (protobuf/int->boolean (protobuf/default tpatlas-animation-pb-cls :flip-horizontal))))
  (property flip-vertical g/Bool (default (protobuf/int->boolean (protobuf/default tpatlas-animation-pb-cls :flip-vertical))))
  (property playback types/AnimationPlayback (default (protobuf/default tpatlas-animation-pb-cls :playback))
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Tile$Playback))))

  (input image-node-id+original-names NodeID+OriginalName :array :cascade-delete)
  (input tpinfo-image-scenes-by-original-name OriginalName->Scene)

  (input tpinfo-image-infos-by-original-name OriginalName->ImageInfo)
  (output tpinfo-image-infos-by-original-name OriginalName->ImageInfo (gu/passthrough tpinfo-image-infos-by-original-name))

  (input rename-patterns g/Str)
  (input id-counts FinalNameCounts) ; A map from final name to frequency (to detect duplicate names)
  (input anim-data g/Any)
  (input gpu-texture g/Any)

  (output image-scenes SceneVec :cached
          (g/fnk [tpinfo-image-scenes-by-original-name image-node-id+original-names updatable]
            ;; The animation includes copies of the referenced tpinfo image
            ;; scenes so that animation images can be highlighted and selected.
            ;; The image scenes only include outlines and shapes for selection
            ;; picking. The actual "sprite sheet" page images are drawn from the
            ;; tpinfo scene as quads. The page offset transforms have been baked
            ;; into the image scenes, so they can be used as children to either
            ;; the TPInfoNode or AtlasAnimationNode scenes.
            (into []
                  (keep-indexed
                    (fn [frame [image-node-id original-name]]
                      (some-> original-name
                              tpinfo-image-scenes-by-original-name
                              (dissoc :children)
                              (assoc-in [:renderable :user-data :frame] frame)
                              (assoc :node-id image-node-id
                                     :updatable updatable))))
                  image-node-id+original-names)))

  (output image-outlines g/Any :cached
          (g/fnk [image-node-id+original-names rename-patterns id-counts]
            (mapv (fn [[node-id original-name]]
                    (let [final-name (rename-id original-name rename-patterns)
                          has-duplicate-name (> (long (id-counts final-name)) 1)]
                      {:node-id node-id
                       :node-outline-key original-name
                       :label final-name
                       :icon image-icon
                       :outline-error? has-duplicate-name}))
                  image-node-id+original-names)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id id own-build-errors image-outlines]
            {:node-id _node-id
             :node-outline-key id
             :label id
             :icon animation-icon
             :outline-error? (g/error-fatal? own-build-errors)
             :child-reqs [{:node-type AtlasImageNode
                           :tx-attach-fn attach-image-to-animation}]
             :children image-outlines}))

  (output save-value g/Any :cached produce-animation-save-value)
  (output updatable SceneUpdatable :cached produce-animation-updatable)
  (output scene Scene :cached produce-animation-scene)

  (output own-build-errors g/Any
          (g/fnk [_node-id fps id id-counts]
            (g/package-errors _node-id
                              (validate-animation-id _node-id id id-counts)
                              (validate-animation-fps _node-id fps))))

  (output image-build-errors g/Any :cached
          (g/fnk [image-node-id+original-names tpinfo-image-infos-by-original-name]
            (into []
                  (keep (fn [[node-id original-name]]
                          (validate-original-name node-id original-name tpinfo-image-infos-by-original-name)))
                  image-node-id+original-names)))

  (output build-errors g/Any :cached
          (g/fnk [_node-id own-build-errors image-build-errors]
            (g/package-errors _node-id
                              own-build-errors
                              image-build-errors))))

(defn- add-image-nodes-to-animation-node [animation-node image-names]
  (let [graph-id (g/node-id->graph-id animation-node)]
    (for [image-name image-names]
      (g/make-nodes
        graph-id
        [atlas-image [AtlasImageNode :original-name image-name]]
        (attach-image-to-animation animation-node atlas-image)))))

(defn- add-atlas-animation-node [atlas-node anim]
  {:pre [(map? anim)]} ; Atlas$AtlasAnimation in map format.
  (let [graph-id (g/node-id->graph-id atlas-node)
        image-names (:images anim)]
    (g/make-nodes
      graph-id
      [animation-node AtlasAnimationNode]
      (concat
        (gu/set-properties-from-pb-map animation-node tpatlas-animation-pb-cls anim
          id :id
          flip-horizontal :flip-horizontal
          flip-vertical :flip-vertical
          fps :fps
          playback :playback)
        (attach-animation-to-atlas atlas-node animation-node)
        (add-image-nodes-to-animation-node animation-node image-names)))))

;; .tpatlas file
(defn- load-tpatlas-file [project self resource tpatlas]
  {:pre [(map? tpatlas)]} ; Atlas$AtlasDesc in map format.
  (let [resolve-resource #(workspace/resolve-resource resource %)
        tx-data (concat
                  (g/connect project :build-settings self :build-settings)
                  (g/connect project :texture-profiles self :texture-profiles)
                  (gu/set-properties-from-pb-map self tpatlas-pb-cls tpatlas
                    file (resolve-resource :file)
                    rename-patterns :rename-patterns
                    is-paged-atlas :is-paged-atlas)
                  (mapv (fn [animation]
                          (->> animation
                               (update-int->bool [:flip-horizontal :flip-vertical])
                               (add-atlas-animation-node self)))
                        (:animations tpatlas)))]
    tx-data))

;; saving the .tpatlas file
(g/defnk produce-tpatlas-save-value [file animation-save-values rename-patterns is-paged-atlas]
  (protobuf/make-map-without-defaults tpatlas-pb-cls
    :file (resource/resource->proj-path file)
    :rename-patterns rename-patterns
    :is-paged-atlas is-paged-atlas
    :animations animation-save-values))

(defn- validate-rename-patterns [node-id rename-patterns]
  (try
    (AtlasUtil/validatePatterns rename-patterns)
    (catch Exception error
      (validation/prop-error :fatal node-id :rename-patterns identity (.getMessage error)))))

(defn- validate-unique-ids [node-id id-counts]
  (validation/prop-error
    :fatal node-id :rename-patterns
    (fn [duplicate-ids]
      (when (pos? (count duplicate-ids))
        (str "The following ids are not unique:\n"
             (string/join "\n" (map dialogs/indent-with-bullet duplicate-ids)))))
    (into (sorted-set)
          (keep (fn [[id ^long frequency]]
                  (when (> frequency 1)
                    id)))
          id-counts)))

(defn- tpinfo-has-multiple-pages? [tpinfo]
  (if (nil? tpinfo)
    false
    (> (count (:pages tpinfo)) 1)))

(defn- build-texture [resource _dep-resources user-data]
  (let [{:keys [page-image-content-generators]} user-data
        buffered-images (mapv texture-util/call-generator page-image-content-generators)]
    (g/precluding-errors buffered-images
      (let [{:keys [paged-atlas texture-profile compress]} user-data
            path (resource/path resource)
            texture-profile-pb (some->> texture-profile (protobuf/map->pb Graphics$TextureProfile))
            texture-generator-result (plugin-create-texture path paged-atlas buffered-images texture-profile-pb compress)]
        {:resource resource
         :write-content-fn tex-gen/write-texturec-content-fn
         :user-data {:texture-generator-result texture-generator-result}}))))

(defn- make-texture-build-target
  [workspace node-id paged-atlas page-image-content-generators texture-profile compress]
  {:pre [(g/node-id? node-id)
         (boolean? paged-atlas)
         (coll? page-image-content-generators) ; Content generators for the page images: page-0.png, page-1.png etc
         (every? texture-util/content-generator? page-image-content-generators)
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
          dep-build-targets [texture-build-target]]
      [(pipeline/make-protobuf-build-target
         _node-id resource TextureSetProto$TextureSet
         (assoc texture-set :texture texture-resource)
         dep-build-targets)])))

(g/defnk produce-anim-data [texture-set uv-transforms]
  (if (empty? (:animations texture-set))
    {}
    (texture-set/make-anim-data texture-set uv-transforms)))

(defn- modify-tpinfo-image-node-outline [tpinfo-image-node-outline rename-patterns id-counts]
  (let [original-name (:node-outline-key tpinfo-image-node-outline)
        final-name (rename-id original-name rename-patterns)
        has-duplicate-name (> (long (id-counts final-name)) 1)]
    (assoc tpinfo-image-node-outline
      :label final-name
      :outline-error? has-duplicate-name)))

;; We want to reuse the node outlines from the tpinfo file, but we also
;; need them to display any renamed image names
(defn- make-tpinfo-node-outline-copies [tpinfo-node-outline rename-patterns id-counts]
  (into []
        (comp (mapcat (fn [page-node-outline]
                        (:children page-node-outline)))
              (map (fn [image-node-outline]
                     (modify-tpinfo-image-node-outline image-node-outline rename-patterns id-counts))))
        (:children tpinfo-node-outline)))

(set! *warn-on-reflection* false)
(defn- make-uv-transforms+texture-set [^String path atlas]
  (let [result (plugin-create-texture-set-result path atlas "")
        texture-set (protobuf/pb->map-without-defaults (.left result)) ; Reflection from (.left) call on unknown type.
        uv-transforms (vec (.right result))] ; Reflection from (.right) call on unknown type.
    (pair uv-transforms texture-set)))
(set! *warn-on-reflection* true)

(g/defnode TPAtlasNode
  (inherits resource-node/ResourceNode)

  (property file resource/Resource
            (value (gu/passthrough tpinfo-file-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :tpinfo-file-resource]
                                            [:node-outline :tpinfo-node-outline]
                                            [:image-infos-by-original-name :tpinfo-image-infos-by-original-name]
                                            [:image-scenes-by-original-name :tpinfo-image-scenes-by-original-name]
                                            [:save-value :tpinfo]
                                            [:page-image-content-generators :tpinfo-page-image-content-generators]
                                            [:build-errors :tpinfo-build-errors]
                                            [:scene :tpinfo-scene])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext tpinfo-file-ext}))
            (dynamic error (g/fnk [_node-id file]
                             (validate-tpinfo-file _node-id file))))

  (property size types/Vec2
            (value (g/fnk [tpinfo] (tpinfo->size-vec2 tpinfo)))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property rename-patterns g/Str (default (protobuf/default tpatlas-pb-cls :rename-patterns))
            (dynamic error (g/fnk [_node-id rename-patterns]
                             (validate-rename-patterns _node-id rename-patterns))))

  ;; User setting, to manually choose if an atlas with a single page should use a texture array or not.
  (property is-paged-atlas g/Bool (default (protobuf/default tpatlas-pb-cls :is-paged-atlas))
            (dynamic visible (g/fnk [tpinfo] (not (tpinfo-has-multiple-pages? tpinfo)))))

  (input build-settings g/Any)
  (input texture-profiles g/Any)

  (input animation-save-values g/Any :array) ; Array of texture packer Atlas$AtlasAnimation protobuf messages in map format for each manually created animation.
  (input animation-ids g/Str :array) ; Array of the manually created animation ids.
  (input animation-scenes Scene :array)
  (input animation-build-errors g/Any :array)

  (input tpinfo g/Any)
  (input tpinfo-build-errors g/Any)
  (input tpinfo-file-resource resource/Resource)
  (input tpinfo-page-image-content-generators g/Any) ; A vector with a content-generator for each page image png file. Each generates a BufferedImage.
  (input tpinfo-node-outline g/Any)
  (input tpinfo-scene Scene)

  (input tpinfo-image-infos-by-original-name OriginalName->ImageInfo)
  (output tpinfo-image-infos-by-original-name OriginalName->ImageInfo (gu/passthrough tpinfo-image-infos-by-original-name))

  (input tpinfo-image-scenes-by-original-name OriginalName->Scene)
  (output tpinfo-image-scenes-by-original-name OriginalName->Scene (gu/passthrough tpinfo-image-scenes-by-original-name))

  (output use-texture-array g/Bool
          (g/fnk [tpinfo is-paged-atlas]
            (or (tpinfo-has-multiple-pages? tpinfo) is-paged-atlas)))

  (output texture-page-count g/Int ; Atlas node protocol.
          (g/fnk [tpinfo use-texture-array]
            (if use-texture-array
              (count (:pages tpinfo))
              texture/non-paged-page-count)))

  (output uv-transforms+texture-set g/Any :cached
          (g/fnk [_node-id resource save-value tpinfo tpinfo-file-resource]
            (or (validate-tpinfo-file _node-id tpinfo-file-resource)
                (validate-tpinfo-for-tpatlas-use _node-id tpinfo)
                (when-some [rename-patterns (:rename-patterns save-value)] ; Stripped from save-value if empty.
                  (validate-rename-patterns _node-id rename-patterns))
                (let [path (resource/path resource)
                      tpatlas-bytes (protobuf/map->bytes tpatlas-pb-cls save-value)
                      tpinfo-bytes (protobuf/map->bytes tpinfo-pb-cls tpinfo)
                      atlas (plugin-create-full-atlas path tpatlas-bytes tpinfo-bytes)]
                  (make-uv-transforms+texture-set path atlas)))))

  (output uv-transforms g/Any (g/fnk [uv-transforms+texture-set] (first uv-transforms+texture-set)))
  (output texture-set g/Any (g/fnk [uv-transforms+texture-set] (second uv-transforms+texture-set)))
  (output anim-data g/Any :cached produce-anim-data) ; Atlas node protocol.

  (output texture-profile g/Any
          (g/fnk [texture-profiles resource]
            (tex-gen/match-texture-profile texture-profiles (resource/proj-path resource))))

  (output gpu-texture g/Any :cached ; Atlas node protocol.
          (g/fnk [_node-id tpinfo-page-image-content-generators texture-profile]
            (make-gpu-texture _node-id tpinfo-page-image-content-generators texture-profile)))

  (output anim-ids g/Any :cached ; Atlas node protocol.
          (g/fnk [animation-ids rename-patterns tpinfo-image-infos-by-original-name]
            (->> tpinfo-image-infos-by-original-name
                 (keys)
                 (map #(rename-id % rename-patterns))
                 (concat animation-ids)
                 (filter not-empty)
                 (sort util/natural-order)
                 (vec))))

  (output id-counts FinalNameCounts :cached
          (g/fnk [anim-ids]
            (frequencies anim-ids)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id tpinfo-node-outline rename-patterns id-counts child-outlines own-build-errors]
            {:node-id _node-id
             :node-outline-key tpatlas-resource-label
             :label tpatlas-resource-label
             :icon tpatlas-icon
             :outline-error? (g/error-fatal? own-build-errors)
             :children (into (make-tpinfo-node-outline-copies tpinfo-node-outline rename-patterns id-counts)
                             child-outlines)}))

  (output save-value g/Any :cached produce-tpatlas-save-value)
  (output build-targets g/Any :cached produce-tpatlas-build-targets) ; Atlas node protocol.
  (output scene g/Any :cached produce-tpatlas-scene)

  (output own-build-errors g/Any
          (g/fnk [_node-id file rename-patterns id-counts]
            (g/package-errors _node-id
                              (validate-tpinfo-file _node-id file)
                              (validate-rename-patterns _node-id rename-patterns)
                              (validate-unique-ids _node-id id-counts))))

  (output build-errors g/Any
          (g/fnk [_node-id animation-build-errors own-build-errors tpinfo-build-errors]
            (g/package-errors _node-id
                              own-build-errors
                              animation-build-errors
                              tpinfo-build-errors))))

;; *****************************************************************************
;; Outline handlers

(defn- selection->atlas [selection evaluation-context] (handler/adapt-single selection TPAtlasNode evaluation-context))
(defn- selection->animation [selection evaluation-context] (handler/adapt-single selection AtlasAnimationNode evaluation-context))
(defn- selection->image [selection evaluation-context] (handler/adapt-single selection AtlasImageNode evaluation-context))

(defn- image->owning-animation [basis image-node-id]
  (when-some [owner-node-id (ffirst (g/targets-of basis image-node-id :node-id+original-name))]
    (when (g/node-instance? basis AtlasAnimationNode owner-node-id)
      owner-node-id)))

(defn- add-animation-group-handler [app-view atlas-node]
  (let [op-seq (gensym)
        [animation-node] (g/tx-nodes-added
                           (g/transact
                             (concat
                               (g/operation-sequence op-seq)
                               (g/operation-label (localization/message "operation.atlas.add-animation"))
                               (add-atlas-animation-node atlas-node default-animation))))]
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (app-view/select app-view [animation-node])))))

(handler/defhandler :edit.add-embedded-component :workbench
  :label (localization/message "command.edit.add-embedded-component.variant.atlas")
  (active? [selection evaluation-context] (selection->atlas selection evaluation-context))
  (run [app-view selection]
    (g/let-ec [atlas-node (selection->atlas selection evaluation-context)]
      (add-animation-group-handler app-view atlas-node))))

(defn- add-images-dialog-filter-fn [filter-text unfiltered-options]
  (fuzzy-choices/filter-options :original-name :original-name filter-text unfiltered-options))

(defn- add-images-dialog-cell-fn [option _localization]
  (let [matching-indices (:matching-indices (meta option))]
    {:graphic {:fx/type resource-dialog/matched-list-item-view
               :icon image-icon
               :text (:original-name option)
               :matching-indices matching-indices}}))

(def ^:private add-images-dialog-opts
  {:title "Select Animation Frames"
   :ok-label "Add Animation Frames"
   :selection :multiple
   :filter-fn add-images-dialog-filter-fn
   :cell-fn add-images-dialog-cell-fn})

(defn- show-add-images-dialog! [original-names localization]
  (let [options
        (->> original-names
             (sort util/natural-order)
             (mapv (fn [original-name]
                     ;; We want to feed something that supports metadata into
                     ;; the :filter-fn so it can decorate the matched options
                     ;; with :matching-indices metadata.
                     {:original-name original-name})))

        selected-options
        (dialogs/make-select-list-dialog options localization add-images-dialog-opts)]

    (when (seq selected-options)
      (mapv :original-name selected-options))))

(defn- add-images-handler [app-view localization animation-node]
  {:pre [(g/node-instance? AtlasAnimationNode animation-node)]}
  (when-some [original-names (some-> animation-node
                                     (g/maybe-node-value :tpinfo-image-infos-by-original-name)
                                     (keys)
                                     (not-empty))]
    (when-some [selected-original-names (show-add-images-dialog! original-names localization)]
      (let [op-seq (gensym)
            image-nodes (g/tx-nodes-added
                          (g/transact
                            (concat
                              (g/operation-sequence op-seq)
                              (g/operation-label "Add Animation Frames")
                              (add-image-nodes-to-animation-node animation-node selected-original-names))))]
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (app-view/select app-view image-nodes)))))))

(handler/defhandler :edit.add-referenced-component :workbench
  (label [] "Add Animation Frames...")
  (active? [selection evaluation-context] (selection->animation selection evaluation-context))
  (run [app-view localization project selection workspace]
    (g/let-ec [animation-node (selection->animation selection evaluation-context)]
      (when animation-node
        (add-images-handler app-view localization animation-node)))))

(defn- vec-move
  ^List [^List vector item ^long offset]
  (let [current-index (.indexOf vector item)
        new-index (max 0 (+ current-index offset))
        [before after] (split-at new-index (remove #(= item %) vector))]
    (vec (concat before [item] after))))

(defn- move-node!
  [parent-node-id child-node-id parent->children ^long offset]
  (let [children (parent->children parent-node-id)
        new-children (vec-move children child-node-id offset)
        connections (keep (fn [[source source-label target target-label]]
                            (when (and (= source child-node-id)
                                       (= target parent-node-id))
                              [source-label target-label]))
                          (g/outputs child-node-id))]
    (g/transact
      (concat
        (for [child children
              [source target] connections]
          (g/disconnect child source parent-node-id target))
        (for [child new-children
              [source target] connections]
          (g/connect child source parent-node-id target))))))

(defn- animation->image-node-ids
  (^List [animation-node-id]
   (g/with-auto-evaluation-context evaluation-context
     (animation->image-node-ids animation-node-id evaluation-context)))
  (^List [animation-node-id evaluation-context]
   (mapv first
         (g/node-value animation-node-id :image-node-id+original-names evaluation-context))))

(defn- move-animation-image! [selection ^long offset]
  (g/let-ec [basis (:basis evaluation-context)
             image-node-id (selection->image selection evaluation-context)
             animation-node-id (image->owning-animation basis image-node-id)]
    (move-node! animation-node-id image-node-id animation->image-node-ids offset)))

(defn- move-active? [selection {:keys [basis] :as evaluation-context}]
  (some->> (selection->image selection evaluation-context)
           (image->owning-animation basis)))

(defn- move-enabled? [selection ^long offset {:keys [basis] :as evaluation-context}]
  (let [image-node-id (selection->image selection evaluation-context)
        animation-node-id (image->owning-animation basis image-node-id)
        animation-image-node-ids (animation->image-node-ids animation-node-id evaluation-context)
        animation-image-index (.indexOf animation-image-node-ids image-node-id)]
    (<= 0 (+ animation-image-index offset) (dec (.size animation-image-node-ids)))))

(handler/defhandler :edit.reorder-up :workbench
  (active? [selection evaluation-context] (move-active? selection evaluation-context))
  (enabled? [selection evaluation-context] (move-enabled? selection -1 evaluation-context))
  (run [selection] (move-animation-image! selection -1)))

(handler/defhandler :edit.reorder-down :workbench
  (active? [selection evaluation-context] (move-active? selection evaluation-context))
  (enabled? [selection evaluation-context] (move-enabled? selection 1 evaluation-context))
  (run [selection] (move-animation-image! selection 1)))

;; *****************************************************************************

(defn- register-resource-types [workspace]
  (concat
    (resource-node/register-ddf-resource-type workspace
      :ext tpinfo-file-ext
      :label tpinfo-resource-label
      :node-type TPInfoNode
      :load-fn load-tpinfo-file
      :icon tpinfo-icon
      :category (localization/message "resource.category.resources")
      :ddf-type tpinfo-pb-cls
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}})
    (resource-node/register-ddf-resource-type workspace
      :ext tpatlas-file-ext
      :build-ext "a.texturesetc"
      :label tpatlas-resource-label
      :node-type TPAtlasNode
      :ddf-type tpatlas-pb-cls
      :load-fn load-tpatlas-file
      :icon tpatlas-icon
      :category (localization/message "resource.category.resources")
      :view-types [:scene :text]
      :view-opts {:scene {:grid true}}
      :template "/texturepacker/editor/resources/templates/template.tpatlas")))

;; The plugin
(defn- load-plugin-texturepacker [workspace]
  (g/transact
    (concat
      (register-resource-types workspace)
      (workspace/register-resource-kind-extension workspace :atlas "tpatlas"))))

(defn- return-plugin []
  (fn [x] (load-plugin-texturepacker x)))

(return-plugin)
