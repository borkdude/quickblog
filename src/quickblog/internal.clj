(ns quickblog.internal
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [markdown.core :as md]
   [selmer.parser :as selmer]))

;; Script used for live reloading in watch mode
(def live-reload-script "https://livejs.com/live.js")

(set! *warn-on-reflection* true)

(defn- last-modified-1
  "Returns max last-modified of regular file f. Returns 0 if file does not exist."
  ^java.nio.file.attribute.FileTime [f]
  (if (fs/exists? f)
    (fs/last-modified-time f)
    (java.nio.file.attribute.FileTime/fromMillis 0)))

(defn max-filetime [filetimes]
  (if (empty? filetimes)
    (java.nio.file.attribute.FileTime/fromMillis 0)
    (reduce #(if (pos? (.compareTo ^java.nio.file.attribute.FileTime %1 ^java.nio.file.attribute.FileTime %2))
               %1 %2)
            filetimes)))

(defn- last-modified
  "Returns max last-modified of f or of all files within f"
  [f]
  (if (fs/exists? f)
    (if (fs/regular-file? f)
      (last-modified-1 f)
      (max-filetime
             (map last-modified-1
                  (filter fs/regular-file? (file-seq (fs/file f))))))
    (java.nio.file.attribute.FileTime/fromMillis 0)))

(defn- expand-file-set
  [file-set]
  (if (coll? file-set)
    (mapcat expand-file-set file-set)
    (filter fs/regular-file? (file-seq (fs/file file-set)))))

(defn modified-since
  "Returns seq of regular files (non-directories, non-symlinks) from file-set that were modified since the anchor path.
  The anchor path can be a regular file or directory, in which case
  the recursive max last modified time stamp is used as the timestamp
  to compare with.  The file-set may be a regular file, directory or
  collection of files (e.g. returned by glob). Directories are
  searched recursively."
  [anchor file-set]
  (let [lm (last-modified anchor)]
    (map fs/path (filter #(pos? (.compareTo (last-modified-1 %) lm)) (expand-file-set file-set)))))

(def ^:private cache-filename "cache.edn")
(def ^:private resource-path "quickblog")
(def ^:private templates-resource-dir "templates")
(def ^:private favicon-template "favicon.html")

(def ^:private metadata-transformers
  {:default first
   :tags #(if (empty? %) #{} (-> % first (str/split #",\s*") set))})

(def ^:private required-metadata
  #{:date
    :title})

(defmacro ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

(defn rendering-modified? [target-file rendering-system-files]
  (seq (modified-since target-file rendering-system-files)))

(defn copy-modified [src target]
  (when (seq (modified-since target src))
    (println "Writing" (str target))
    (fs/create-dirs (.getParent (fs/file target)))
    (fs/copy src target {:replace-existing true})))

(defn copy-tree-modified [src-dir target-dir]
  (let [src-dir (fs/file src-dir)
        target-dir (fs/file target-dir)
        num-dirs (->> src-dir .toPath .iterator iterator-seq count)
        from-src-dir (fn [path]
                       (->> path .iterator iterator-seq (drop num-dirs) (apply fs/file)))
        modified-paths (modified-since target-dir src-dir)
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
       (fs/copy (io/resource (str (fs/file resource-path source-file))) target-file))
     target-file)))

(defn ensure-template [{:keys [templates-dir]} template-name]
  (ensure-resource (fs/file templates-dir template-name)
                   (fs/file templates-resource-dir template-name)))

(defn refresh-templates [{:keys [templates-dir] :as opts}]
  (doseq [template (map fs/file (fs/glob templates-dir "*"))
          :let [template-name (fs/file-name template)
                resource (fs/file resource-path templates-resource-dir template-name)]]
    (if (io/resource (str resource))
      (do
        (println "Refreshing template:" (str template))
        (fs/delete template)
        (ensure-template opts template-name))
      (println "Skipping custom template:" (str template)))))

(defn blog-link [{:keys [blog-root]} relative-url]
  (when relative-url
    (format "%s%s%s"
            blog-root
            (if (str/ends-with? blog-root "/") "" "/")
            relative-url)))

(defn html-file [file]
  (str/replace file ".md" ".html"))

(defn cache-file [file]
  (str file ".pre-template.html"))

(defn escape-tag [tag]
  (str/replace tag #"[^A-z0-9]" "-"))

(defn tag-file [tag]
  (-> tag
      escape-tag
      (str ".html")))

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
      ;; allow multiline link title
      (str/replace #"\[[^\]]+\n"
                   (fn [match]
                     (str/replace match "\n" "$$RET$$")))))

(defn post-process-markdown [html]
  (-> html
      ;; restore comments
      (str/replace #"(<p>)?<!&ndash;(.*?)&ndash;>(</p>)?" "<!--$2-->")
      ;; restore newline in multiline link titles
      (str/replace "$$RET$$" "\n")))

(defn markdown->html [file]
  (let [markdown (slurp file)]
    (println "Processing markdown for file:" (str file))
    (-> markdown
        pre-process-markdown
        (md/md-to-html-string-with-meta :reference-links? true
                                        :heading-anchors true
                                        :footnotes? true
                                        :code-style
                                        (fn [lang]
                                          (format "class=\"lang-%s language-%s\"" lang lang))
                                        :pre-style
                                        (fn [lang]
                                          (format "class=\"language-%s\"" lang)))
        :html
        post-process-markdown)))

(defn remove-previews [posts]
  (->> posts
       (remove (fn [{:keys [file preview]}]
                 (let [preview? (Boolean/valueOf preview)]
                   (when preview?
                     (println "Skipping preview post:" file)
                     true))))))

(defn post-compare [a-post b-post]
  ;; Compare dates opposite the other values to force desending order
    (compare [(:date b-post) (:title a-post) (:file a-post)]
             [(:date a-post) (:title b-post) (:file b-post)]))

(defn sort-posts [posts]
  (sort post-compare posts))

(defn modified-since? [target src]
  (seq (modified-since target src)))

(defn validate-metadata [post]
  (if-let [missing-keys
           (seq (set/difference required-metadata
                                (set (keys post))))]
    {:quickblog/error (format "Skipping %s due to missing required metadata: %s"
                              (:file post) (str/join ", " (map name missing-keys)))}
    post))

(defn read-cached-post [{:keys [cache-dir]} file]
  (let [cached-file (fs/file cache-dir (cache-file file))]
    (delay
      (println "Reading post from cache:" (str file))
      (slurp cached-file))))

(defn load-post [{:keys [cache-dir default-metadata
                         force-render rendering-system-files]
                  :as opts}
                 path]
  (let [path (fs/file path)
        file (fs/file-name path)
        cached-file (fs/file cache-dir (cache-file file))
        stale? (or force-render
                   (not (fs/exists? cached-file))
                   (rendering-modified? cached-file (cons path rendering-system-files)))]
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
                         (read-cached-post opts file)))
          validate-metadata)
      (catch Exception e
        {:quickblog/error (format "Skipping post %s due to exception: %s"
                                  (str file) (str e))}))))

(defn ->filename [path]
  (-> path fs/file fs/file-name))

(defn has-error? [_opts [_ {:keys [quickblog/error]}]]
  (when error
    (println error)
    true))

(defn debug [& xs]
  (binding [*out* *err*]
    (apply println xs)))

(defn load-posts [{:keys [cache-dir cached-posts posts-dir] :as opts}]
  (if (fs/exists? posts-dir)
    (let [cache-file (fs/file cache-dir cache-filename)
          post-paths (set (fs/glob posts-dir "*.md"))
          modified-post-paths (if (empty? cached-posts)
                                (set post-paths)
                                (set (modified-since cache-file post-paths)))
          _ (when (fs/exists? cache-file) (debug :filetime-cache-file (fs/last-modified-time cache-file)))
          _ (debug :post-paths post-paths)
          _ (debug :filetime-posts (map fs/last-modified-time post-paths))
          _ (debug :modified-post-paths modified-post-paths)
          _cached-post-paths (set/difference post-paths modified-post-paths)]
      (merge (->> cached-posts
                  (map (fn [[file post]]
                         [file (assoc post :html (read-cached-post opts file))]))
                  (into {}))
             (->> modified-post-paths
                  (map (juxt ->filename (partial load-post opts)))
                  (remove (partial has-error? opts))
                  (into {}))))
    {}))

(defn only-metadata [posts]
  (->> posts
       (map (fn [[file post]] [file (dissoc post :html)]))
       (into {})))

(defn load-cache [{:keys [cache-dir rendering-system-files]}]
  (let [cache-file (fs/file cache-dir cache-filename)]
    ;; Invalidate the cache if the rendering system has been modified
    (if (or (rendering-modified? cache-file rendering-system-files)
            (not (fs/exists? cache-file)))
      {}
      (edn/read-string (slurp cache-file)))))

(defn write-cache! [{:keys [cache-dir posts]}]
  (let [cache-file (fs/file cache-dir cache-filename)]
    (fs/create-dirs cache-dir)
    (prn :writing-cache (only-metadata posts))
    (spit cache-file (only-metadata posts))))

(defn deleted-posts [{:keys [cached-posts posts]}]
  (->> [cached-posts posts]
       (map (comp set keys))
       (apply set/difference)))

(defn modified-metadata [{:keys [cached-posts posts]}]
  (let [cached-posts (only-metadata cached-posts)
        posts (only-metadata posts)
        _ (debug :post-meta posts)
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

(defn modified-posts
  [{:keys [force-render out-dir posts cached-posts posts-dir rendering-system-files]}]
  (->> posts
       (filter (fn [[file post]]
                 (let [out-file (fs/file out-dir (html-file file))
                       post-file (fs/file posts-dir file)]
                   (or force-render
                       (rendering-modified? out-file
                                            (cons post-file rendering-system-files))
                       (not= (select-keys post [:prev :next])
                             (select-keys (cached-posts file) [:prev :next]))))))
       (map first)
       set))

(defn posts-with-modified-draft-statuses [{:keys [modified-metadata]}]
  (debug :modified-metadata-for-modified-drafts modified-metadata)
  (->> (vals modified-metadata)
       (mapcat (partial keep (fn [[post opts]]
                               (when (contains? opts :preview)
                                 post))))))

(defn modified-tags [{:keys [posts modified-metadata modified-drafts]}]
  (let [tags-from-modified-drafts (map :tags (vals (select-keys posts modified-drafts)))]
    (->> (vals modified-metadata)
         (mapcat (partial map (fn [[_ {:keys [tags]}]] tags)))
         (concat tags-from-modified-drafts)
         (apply set/union))))

(defn expand-prev-next-metadata [{:keys [link-prev-next-posts posts] :as _opts}
                                 {:keys [prev next] :as post}]
  (if link-prev-next-posts
    (let [posts (if (map? posts)
                  posts
                  (->> posts
                       (map (fn [{:keys [file] :as post}] [file post]))
                       (into {})))]
      (merge post {:next (posts next), :prev (posts prev)}))
    post))

(defn assoc-prev-next
  "If the `:link-prev-next-posts` opt is true, adds to each post a :prev key
   pointing to the filename of the previous post by date and a :next key pointing
   to the filename of the next post by date. Preview posts are skipped unless the
  `:include-preview-posts-in-linking` is true."
  [{:keys [posts link-prev-next-posts include-preview-posts-in-linking]
    :as opts}]
  (if link-prev-next-posts
    (let [remove-preview-posts (if include-preview-posts-in-linking
                                 identity
                                 #(remove (comp :preview val) %))
          post-keys (->> posts
                         remove-preview-posts
                         (sort-by (comp :date val))
                         (mapv first))]
      (assoc opts :posts
             ;; We need to merge the linked posts on top of the original ones
             ;; so that preview posts are still present even when they're
             ;; excluded from linking
             (merge posts
                    (->> post-keys
                         (map-indexed
                          (fn [i k]
                            [k (assoc (posts k)
                                      :prev (when (pos? i)
                                              (post-keys (dec i)))
                                      :next (when (< i (dec (count post-keys)))
                                              (post-keys (inc i))))]))
                         (into {})))))
    opts))

(defn refresh-cache [{:keys [cached-posts posts] :as opts}]
  ;; watch mode manages caching manually, so if cached-posts and posts are
  ;; already set, use them as is
  (let [cached-posts (if cached-posts
                       cached-posts
                       (load-cache opts))
        opts (assoc opts :cached-posts cached-posts)
        posts (if posts
                posts
                (load-posts opts))
        opts (assoc opts :posts posts)
        opts (assoc-prev-next opts)
        opts (assoc opts :modified-metadata (modified-metadata opts))
        modified-drafts (distinct (posts-with-modified-draft-statuses opts))
        opts (assoc opts :modified-drafts modified-drafts)]
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
       remove-previews
       (sort-by :date)
       (mapcat (fn [{:keys [tags] :as post}]
                 (map (fn [tag] [tag post]) tags)))
       (reduce (fn [acc [tag post]]
                 (update acc tag #(conj % post)))
               {})))

(defn- load-favicon [{:keys [favicon
                             templates-dir]
                      :as opts}]
  (when favicon
    (-> (fs/file templates-dir favicon-template)
        (ensure-resource (fs/file templates-resource-dir favicon-template))
        slurp
        (selmer/render opts))))

(defn archive-links [title posts {:keys [relative-path page-suffix] :as opts}]
  (let [post-links-template (ensure-template opts "archive.html")
        post-links (for [{:keys [file title date preview]} posts
                         :when (not preview)]
                     {:url (str relative-path (str/replace file ".md" page-suffix))
                      :title title
                      :date date})
        by-year (group-by #(subs (:date %) 0 4) post-links)
        post-groups (for [[k v] by-year]
                      {:year (str k)
                       :post-links v})
        post-groups (sort-by (comp parse-long :year) > post-groups)]
    (selmer/render (slurp post-links-template) {:title title
                                                :post-groups post-groups})))

(defn post-links [title posts {:keys [relative-path page-suffix] :as opts}]
  (let [post-links-template (ensure-template opts "post-links.html")
        post-links (for [{:keys [file title date preview]} posts
                         :when (not preview)]
                     {:url (str relative-path (str/replace file ".md" page-suffix))
                      :title title
                      :date date})]
    (selmer/render (slurp post-links-template) {:title title
                                                :post-links post-links})))

(defn tag-links [title tags opts]
  (let [tags-template (ensure-template opts "tags.html")
        tags (map (fn [[tag posts]] {:url (str (escape-tag tag) (:page-suffix opts))
                                     :tag tag
                                     :count (count posts)}) tags)]
    (selmer/render (slurp tags-template) {:title title
                                          :tags (sort-by (comp - :count) tags)})))

(defn render-page [opts template template-vars]
  (let [template-vars (merge opts template-vars)
        template-vars (assoc template-vars
                             :favicon-tags (load-favicon template-vars))]
    (selmer/render template template-vars)))

(defn write-post! [{:keys [twitter-handle
                           discuss-link
                           out-dir
                           page-suffix
                           page-template
                           post-template]
                    :as opts}
                   {:keys [file html description image image-alt]
                    :as post-metadata}]
  (let [out-file (fs/file out-dir (html-file file))
        post-metadata (->> (assoc post-metadata :body @html)
                           (merge {:discuss discuss-link :page-suffix page-suffix})
                           (expand-prev-next-metadata opts))
        body (selmer/render post-template post-metadata)
        author (-> (:twitter-handle post-metadata) (or twitter-handle))
        image (when image (if (re-matches #"^https?://.+" image)
                            image
                            (blog-link opts image)))
        url (blog-link opts (html-file file))
        post-metadata (merge {:sharing (->map description
                                              author
                                              twitter-handle
                                              image
                                              image-alt
                                              url)}
                             (assoc post-metadata :body body))
        rendered-html (render-page opts page-template post-metadata)]
    (println "Writing post:" (str out-file))
    (spit out-file rendered-html)))

(defn write-page! [opts out-file
                   template template-vars]
  (println "Writing page:" (str out-file))
  (->> (render-page opts template template-vars)
       (spit out-file)))

(defn write-tag! [{:keys [blog-title blog-description
                          blog-image blog-image-alt twitter-handle
                          modified-tags] :as opts}
                  tags-out-dir
                  template
                  [tag posts]]
  (let [tag-filename (fs/file tags-out-dir (tag-file tag))]
    (debug :writing-tag tag :modified (set modified-tags) :contains? (contains? (set modified-tags) tag))
    (when (or (contains? (set modified-tags) tag) (not (fs/exists? tag-filename)))
      (write-page! opts tag-filename template
                   {:skip-archive true
                    :title (str blog-title " - Tag - " tag)
                    :relative-path "../"
                    :body (post-links (str "Tag - " tag) posts
                                      (assoc opts :relative-path "../"))
                    :sharing {:description (format "Posts tagged \"%s\" - %s"
                                                   tag blog-description)
                              :author twitter-handle
                              :twitter-handle twitter-handle
                              :image (blog-link opts blog-image)
                              :image-alt blog-image-alt
                              :url (blog-link opts "tags/index.html")}}))))
