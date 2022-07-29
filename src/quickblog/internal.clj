(ns quickblog.internal
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [selmer.parser :as selmer]))

(def ^:private resource-path "quickblog")
(def ^:private templates-resource-dir "templates")
(def ^:private favicon-template "favicon.html")

(def ^:private metadata-transformers
  {:default first
   :tags #(-> % first (str/split #",\s*") set)})

(def ^:private required-metadata
  #{:date
    :title})

;; Cons-ing *file* directly in `rendering-modified?` doesn't work for some reason
(def ^:private this-file (fs/file *file*))

(defn rendering-modified? [rendering-system-files target-file]
  (let [rendering-system-files (cons this-file rendering-system-files)]
    (seq (fs/modified-since target-file rendering-system-files))))

(defn stale? [src target]
  (seq (fs/modified-since target src)))

(defn copy-modified [src target]
  (when (stale? src target)
    (println "Writing" (str target))
    (fs/create-dirs (.getParent (fs/file target)))
    (fs/copy src target {:replace-existing true})))

(defn copy-tree-modified [src-dir target-dir]
  (let [src-dir (fs/file src-dir)
        target-dir (fs/file target-dir)
        num-dirs (->> src-dir .toPath .iterator iterator-seq count)
        from-src-dir (fn [path]
                       (->> path .iterator iterator-seq (drop num-dirs) (apply fs/file)))
        modified-paths (fs/modified-since target-dir src-dir)
        new-paths (->> (fs/glob src-dir "**")
                       (remove #(fs/exists? (fs/file target-dir (from-src-dir %)))))]
    (doseq [path (concat modified-paths new-paths)
            :let [target-path (fs/file target-dir (from-src-dir path))]]
      (fs/create-dirs (.getParent target-path))
      (println "Writing" (str target-path))
      (fs/copy (fs/file path) target-path {:replace-existing true}))))

(defn ensure-resource
  ([path]
   (ensure-resource path path))
  ([target-path source-path]
   (let [target-file (fs/file target-path)
         source-file (fs/file source-path)]
     (when-not (fs/exists? target-file)
       (fs/create-dirs (fs/parent target-file))
       (println "Writing default resource:" (str target-file))
       (fs/copy (io/resource (fs/file resource-path source-file)) target-file))
     target-file)))

(defn ensure-template [{:keys [templates-dir]} template-name]
  (ensure-resource (fs/file templates-dir template-name)
                   (fs/file templates-resource-dir template-name)))

(defn html-file [file]
  (str/replace file ".md" ".html"))

(defn cache-file [file]
  (str file ".pre-template.html"))

(defn transform-metadata
  ([metadata]
   (transform-metadata metadata {}))
  ([metadata default-metadata]
   (->> metadata
        (map (fn [[k v]]
               (let [transformer (or (metadata-transformers k)
                                     (metadata-transformers :default))]
                 [k (transformer v)])))
        (into {})
        (merge default-metadata))))

(defn pre-process-markdown [markdown]
  (-> markdown
      (str/replace #"--" (fn [_] "$$NDASH$$"))
      (str/replace #"\[[^\]]+\n"
                   (fn [match]
                     (str/replace match "\n" "$$RET$$")))))

(defn post-process-markdown [html]
  (-> html
      (str/replace "$$NDASH$$" "--")
      (str/replace "$$RET$$" "\n")))

(defn markdown->html [file]
  (let [markdown (slurp file)]
    (println "Processing markdown for file:" (str file))
    (-> markdown
        pre-process-markdown
        (md/md-to-html-string-with-meta :reference-links? true
                                        :code-style
                                        (fn [lang]
                                          (format "class=\"lang-%s\"" lang)))
        :html
        post-process-markdown)))

(defn sort-posts [posts]
  (sort-by :date (comp - compare) posts))

(defn load-post
  ([file]
   (load-post file {}))
  ([file default-metadata]
   (println "Reading metadata for file:" (str file))
   (try
     (-> (slurp file)
         md/md-to-meta
         (transform-metadata default-metadata)
         (assoc :file (fs/file-name file)
                :html (delay (markdown->html file))))
     (catch Exception e
       (println "Skipping" (str file) "due to exception:" (str e))))))

(defn load-posts
  "Returns all posts from `post-dir` in descending date order"
  ([posts-dir]
   (load-posts posts-dir {}))
  ([posts-dir default-metadata]
   (->> (fs/glob posts-dir "*.md")
        (map #(load-post (fs/file %) default-metadata))
        (remove nil?)
        (remove
         (fn [post]
           (when-let [missing-keys
                      (seq (set/difference required-metadata
                                           (set (keys post))))]
             (println "Skipping" (:file post)
                      "due to missing required metadata:"
                      (str/join ", " (map name missing-keys)))
             :skipping)))
        sort-posts)))

(defn add-modified-metadata
  "Adds :modified? to each post showing if it is new or modified more recently than `out-dir`"
  [posts-dir out-dir posts]
  (let [post-files (map #(fs/file posts-dir (:file %)) posts)
        html-file-exists? #(->> (:file %)
                                html-file
                                (fs/file out-dir)
                                fs/exists?)
        new-posts (->> (remove html-file-exists? posts)
                       (map :file)
                       set)
        modified-posts (->> post-files
                            (fs/modified-since out-dir)
                            (map #(str (.getFileName %)))
                            set)
        new-or-modified-posts (set/union new-posts modified-posts)]
    (map #(assoc %
                 :modified?
                 (contains? new-or-modified-posts
                            (:file %)))
         posts)))

(defn migrate-post [{:keys [default-metadata posts-dir] :as opts}
                    {:keys [file title date tags categories]}]
  (let [post-file (fs/file posts-dir file)
        post (load-post post-file)]
    (if (every? post required-metadata)
      (println (format "Post %s already contains metadata; skipping migration"
                       (str file)))
      (let [contents (slurp post-file)
            tags (or tags categories)
            metadata (assoc default-metadata
                            :title title
                            :date date
                            :tags (str/join "," tags))
            metadata-str (->> metadata
                              (map (fn [[k v]]
                                     (format "%s: %s"
                                             (str/capitalize (name k)) v)))
                              (str/join "\n"))]
        (spit post-file (format "%s\n\n%s" metadata-str contents))
        (println "Migrated file:" (str file))))))

(defn posts-by-tag [posts]
  (->> posts
       (sort-by :date)
       (mapcat (fn [{:keys [tags] :as post}]
                 (map (fn [tag] [tag post]) tags)))
       (reduce (fn [acc [tag post]]
                 (update acc tag #(conj % post)))
               {})))

(defn- load-favicon [{:keys [favicon
                             favicon-dir
                             templates-dir]
                      :as opts}]
  (when favicon
    (-> (fs/file templates-dir favicon-template)
        (ensure-resource (fs/file templates-resource-dir favicon-template))
        slurp
        (selmer/render opts))))

(defn post-links
  ([title posts]
   (post-links title posts {}))
  ([title posts {:keys [relative-path]}]
   [:div {:style "width: 600px;"}
    [:h1 title]
    [:ul.index
     (for [{:keys [file title date preview]} posts
           :when (not preview)]
       [:li [:span
             [:a {:href (str relative-path (str/replace file ".md" ".html"))}
              title]
             " - "
             date]])]]))

(defn tag-links [title tags]
  [:div {:style "width: 600px;"}
   [:h1 title]
   [:ul.index
    (for [[tag posts] tags]
      [:li [:span
            [:a {:href (str tag ".html")} tag]
            " - "
            (count posts)
            " posts"]])]])

(defn render-page [opts template template-vars]
  (let [template-vars (merge opts template-vars)
        template-vars (assoc template-vars
                             :favicon-tags (load-favicon template-vars))]
    (selmer/render template template-vars)))

(defn write-post! [{:keys [bodies
                           discuss-fallback
                           cache-dir
                           out-dir
                           force-render
                           page-template
                           post-template
                           posts-dir
                           rendering-system-files]
                    :as opts}
                   {:keys [file title date discuss tags modified? html]
                    :or {discuss discuss-fallback}}]
  (let [out-file (fs/file out-dir (html-file file))
        markdown-file (fs/file posts-dir file)
        post-modified? (or modified?
                           (rendering-modified? rendering-system-files out-file)
                           force-render)
        cached-file (fs/file cache-dir (cache-file file))
        cached? (and (fs/exists? cached-file) (not post-modified?))
        html (if cached?
               (delay
                 (println "Reading file from cache:" (str cached-file))
                 (slurp cached-file))
               html)
        ;; The index page and XML feed will need the post HTML if they need to
        ;; be re-rendered, so pass it along (but not deferenced, as it may not
        ;; be needed)
        _ (swap! bodies assoc file html)]
    (if post-modified?
      (let [body (selmer/render post-template {:body @html
                                               :title title
                                               :date date
                                               :discuss discuss
                                               :tags tags})
            rendered-html (render-page opts page-template
                                       {:title title
                                        :body body})]
        (println "Writing post:" (str out-file))
        (spit out-file rendered-html)
        (when (and cache-dir (not cached?))
          (println "Writing rendered HTML to cache:" (str cached-file))
          (spit cached-file @html)))
      (println file "not modified; using cached version"))))

(defn write-page! [opts out-file
                   template template-vars]
  (println "Writing page:" (str out-file))
  (->> (render-page opts template template-vars)
       (spit out-file)))

(defn write-tag! [{:keys [blog-title force-render rendering-system-files]
                   :as opts}
                  tags-out-dir
                  template
                  [tag posts]]
  (let [tag-slug (str/replace tag #"[^A-z0-9]" "-")
        tag-file (fs/file tags-out-dir (str tag-slug ".html"))
        stale? (or (rendering-modified? rendering-system-files tags-out-dir)
                   (some :modified? posts)
                   force-render)]
    (when stale?
      (println "Writing tag page:" (str tag-file))
      (write-page! opts tag-file template
                   {:skip-archive true
                    :title (str blog-title " - Tag - " tag)
                    :relative-path "../"
                    :body (hiccup/html (post-links (str "Tag - " tag) posts
                                                   {:relative-path "../"}))}))))
