(ns quickblog.internal
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [selmer.parser :as selmer]))

(def ^:private cache-filename "cache.edn")
(def ^:private resource-path "quickblog")
(def ^:private templates-resource-dir "templates")
(def ^:private favicon-template "favicon.html")

(def ^:private metadata-transformers
  {:default first
   :tags #(-> % first (str/split #",\s*") set)})

(def ^:private required-metadata
  #{:date
    :title})

(defmacro ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

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

(defn modified-since? [target src]
  (seq (fs/modified-since target src)))

(defn validate-metadata [post]
  (if-let [missing-keys
           (seq (set/difference required-metadata
                                (set (keys post))))]
    {:quickblog/error (format "Skipping %s due to missing required metadata: %s"
                              (:file post) (str/join ", " (map name missing-keys)))}
    post))

(defn load-post [{:keys [cache-dir default-metadata
                         force-render rendering-system-files]
                  :as opts}
                 path]
  (let [path (fs/file path)
        file (fs/file-name path)
        cached-file (fs/file cache-dir (cache-file file))
        stale? (or force-render
                   (modified-since? cached-file (cons path rendering-system-files)))]
    (println "Reading metadata for post:" (str file))
    (try
      (-> (slurp path)
          md/md-to-meta
          (transform-metadata default-metadata)
          (assoc :file (fs/file-name file)
                 :html (if stale?
                         (delay
                           (println "Parsing Markdown for post:" (str file))
                           (let [html (markdown->html path)]
                             (println "Caching post to file:" (str cached-file))
                             (spit cached-file html)
                             html))
                         (delay
                           (println "Reading post from cache:" (str file))
                           (slurp cached-file))))
          validate-metadata)
      (catch Exception e
        {:quickblog/error (format "Skipping post %s due to exception: %s"
                                  (str file) (str e))}))))

(defn ->filename [path]
  (-> path fs/file fs/file-name))

(defn has-error? [opts [_ {:keys [quickblog/error]}]]
  (when error
    (println error)
    true))

(defn load-posts [{:keys [posts-dir] :as opts}]
  (->> (fs/glob posts-dir "*.md")
       (map (juxt ->filename (partial load-post opts)))
       (remove (partial has-error? opts))
       (into {})))

(defn only-metadata [posts]
  (->> posts
       (map (fn [[file post]] [file (dissoc post :html)]))
       (into {})))

(defn load-cache [{:keys [cache-dir] :as opts}]
  (let [cache-file (fs/file cache-dir cache-filename)]
    (if (fs/exists? cache-file)
      (edn/read-string (slurp cache-file))
      {})))

(defn write-cache! [{:keys [cache-dir posts] :as opts}]
  (let [cache-file (fs/file cache-dir cache-filename)]
    (fs/create-dirs cache-dir)
    (spit cache-file (only-metadata posts))))

(defn deleted-posts [{:keys [cached-posts posts] :as opts}]
  (->> [cached-posts posts]
       (map (comp set keys))
       (apply set/difference)))

(defn modified-metadata [{:keys [cached-posts posts] :as opts}]
  (let [posts (only-metadata posts)
        [cached current _] (data/diff cached-posts posts)]
    (->map cached current)))

(defn modified-post-pages
  "Returns ids of posts which have newer cache files than post pages"
  [{:keys [cache-dir out-dir posts]}]
  (->> posts
       (filter (fn [[file _]]
                 (let [cached-file (fs/file cache-dir (cache-file file))
                       page-file (fs/file out-dir (html-file file))]
                   (if (fs/exists? cached-file)
                     (modified-since? page-file cached-file)
                     true))))
       (map first)
       set))

(defn modified-posts [{:keys [force-render out-dir posts posts-dir
                              rendering-system-files]
                       :as opts}]
  (->> posts
       (filter (fn [[file _]]
                 (let [out-file (fs/file out-dir (html-file file))
                       post-file (fs/file posts-dir file)]
                   (or force-render
                       (modified-since? out-file
                                        (cons post-file rendering-system-files))))))
       (map first)
       set))

(defn modified-tags [{:keys [modified-metadata] :as opts}]
  (->> (vals modified-metadata)
       (mapcat (partial map (fn [[_ {:keys [tags]}]] tags)))
       (apply set/union)))

(defn refresh-cache [{:keys [force-render
                             cache-dir
                             cached-posts
                             posts
                             rendering-system-files]
                      :as opts}]
  ;; watch mode manages caching manually, so if cached-posts and posts are
  ;; already set, use them as is
  (let [cached-posts (if cached-posts
                       cached-posts
                       (load-cache opts))
        posts (if posts
                posts
                (load-posts opts))
        opts (assoc opts
                    :cached-posts cached-posts
                    :posts posts)
        opts (assoc opts
                    :modified-metadata (modified-metadata opts))]
    (assoc opts
           :deleted-posts (deleted-posts opts)
           :modified-posts (modified-posts opts)
           :modified-tags (modified-tags opts))))

(defn migrate-post [{:keys [default-metadata posts-dir] :as opts}
                    {:keys [file title date tags categories legacy]}]
  (let [post-file (fs/file posts-dir file)
        post (load-post opts post-file)]
    (if (every? post required-metadata)
      (println (format "Post %s already contains metadata; skipping migration"
                       (str file)))
      (let [contents (slurp post-file)
            tags (or tags categories)
            metadata (assoc default-metadata
                            :title title
                            :date date
                            :tags (str/join "," tags))
            metadata (merge metadata
                            (when legacy
                              {:legacy true}))
            metadata-str (->> metadata
                              (map (fn [[k v]]
                                     (format "%s: %s"
                                             (str/capitalize (name k)) v)))
                              (str/join "\n"))]
        (spit post-file (format "%s\n\n%s" metadata-str contents))
        (println "Migrated file:" (str file))))))

(defn posts-by-tag [posts]
  (->> (vals posts)
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

(defn write-post! [{:keys [discuss-fallback
                           cache-dir
                           out-dir
                           force-render
                           page-template
                           post-template
                           posts-dir]
                    :as opts}
                   {:keys [file title date discuss tags html]
                    :or {discuss discuss-fallback}}]
  (let [out-file (fs/file out-dir (html-file file))
        markdown-file (fs/file posts-dir file)
        cached-file (fs/file cache-dir (cache-file file))
        body (selmer/render post-template {:body @html
                                           :title title
                                           :date date
                                           :discuss discuss
                                           :tags tags})
        rendered-html (render-page opts page-template
                                   {:title title
                                    :body body})]
    (println "Writing post:" (str out-file))
    (spit out-file rendered-html)))

(defn write-page! [opts out-file
                   template template-vars]
  (println "Writing page:" (str out-file))
  (->> (render-page opts template template-vars)
       (spit out-file)))

(defn write-tag! [{:keys [blog-title modified-tags] :as opts}
                  tags-out-dir
                  template
                  [tag posts]]
  (let [tag-slug (str/replace tag #"[^A-z0-9]" "-")
        tag-file (fs/file tags-out-dir (str tag-slug ".html"))]
    (when (or (modified-tags tag) (not (fs/exists? tag-file)))
      (write-page! opts tag-file template
                   {:skip-archive true
                    :title (str blog-title " - Tag - " tag)
                    :relative-path "../"
                    :body (hiccup/html (post-links (str "Tag - " tag) posts
                                                   {:relative-path "../"}))}))))
