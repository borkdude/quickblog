(ns quickblog.api
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [quickblog.internal :as lib]
   [selmer.parser :as selmer]))

;; all values should be strings for consistency with command line args
(def ^:private default-opts
  {
   ;; about the blog
   :blog-title "quickblog"
   :blog-author "Quick Blogger"
   :blog-description "A blog about blogging quickly"
   :blog-root "https://github.com/borkdude/quickblog"
   :about-link nil      ; example: "https://github.com/borkdude/quickblog"
   :discuss-link nil    ; example: "https://github.com/borkdude/quickblog/issues"
   :twitter-handle nil  ; example: "quickblogger"
   ;; config
   :default-metadata {}
   :num-index-posts 3
   :posts-file "posts.edn"  ; deprecated, but used for `migrate`
   ;; features
   :favicon "false"
   ;; options
   :force-render "false"
   ;; directories
   :assets-dir "assets"
   :assets-out-dir "assets"
   :blog-dir (fs/file ".")
   :cache-dir ".work"
   :favicon-dir (fs/file "assets" "favicon")
   :favicon-out-dir (fs/file "assets" "favicon")
   :out-dir "public"
   :posts-dir "posts"
   :tags-dir "tags"
   :templates-dir "templates"})

(defn- update-out-dirs
  [{:keys [out-dir assets-out-dir favicon-out-dir] :as opts}]
  (let [out-dir-ify (fn [dir]
                      (if-not (str/starts-with? (str dir) (str out-dir))
                        (fs/file out-dir dir)
                        dir))]
    (assoc opts
           :assets-out-dir (out-dir-ify assets-out-dir)
           :favicon-out-dir (out-dir-ify favicon-out-dir))))

(defn- apply-default-opts [opts]
  (-> (merge default-opts opts)
      (update :favicon #(and % (not= "false" %)))
      (update :force-render #(and % (not= "false" %)))
      update-out-dirs))

(defmacro ^:private ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

(def ^:private favicon-assets
  ["android-chrome-192x192.png"
   "android-chrome-512x512.png"
   "apple-touch-icon.png"
   "browserconfig.xml"
   "favicon-16x16.png"
   "favicon-32x32.png"
   "favicon.ico"
   "mstile-150x150.png"
   "safari-pinned-tab.svg"
   "site.webmanifest"])

(def ^:private rendering-system-files
  [(fs/file "bb.edn") (fs/file "deps.edn") (fs/file *file*)])

;; used for caching
(def ^:private bodies (atom {}))
(def ^:private posts-cache (atom nil))

(def ^:private legacy-template "
<html><head>
<meta http-equiv=\"refresh\" content=\"0; URL=/{{new_url}}\" />
</head></html>")

(defn- base-html [opts]
  (-> (lib/ensure-template opts "base.html")
      slurp))

(defn- ensure-favicon-assets [{:keys [favicon favicon-dir]}]
  (when favicon
    (doseq [asset favicon-assets]
      (lib/ensure-resource (fs/file favicon-dir asset)
                           (fs/file "assets" "favicon" asset)))))

(defn- gen-posts [{:keys [posts discuss-link
                          cache-dir posts-dir out-dir templates-dir]
                   :as opts}]
  (let [page-template (base-html opts)
        post-template (-> (lib/ensure-template opts "post.html")
                          slurp)
        rendering-system-files (concat rendering-system-files
                                       (map fs/file [templates-dir]))]
    (fs/create-dirs cache-dir)
    (fs/create-dirs out-dir)
    (doseq [post posts
            :let [{:keys [file date legacy]} post
                  html-file (lib/html-file file)]]
      (lib/write-post! (assoc opts
                              :bodies bodies
                              :page-template page-template
                              :post-template post-template
                              :rendering-system-files rendering-system-files)
                       post)
      (let [legacy-dir (fs/file out-dir (str/replace date "-" "/")
                                (str/replace file ".md" ""))]
        (when legacy
          (fs/create-dirs legacy-dir)
          (let [redirect-html (selmer/render legacy-template
                                             {:new_url html-file})]
            (spit (fs/file (fs/file legacy-dir "index.html")) redirect-html)))))))

(defn- gen-tags [{:keys [posts blog-title out-dir tags-dir force-render]
                  :as opts}]
  (let [tags-out-dir (fs/create-dirs (fs/file out-dir tags-dir))
        posts-by-tag (lib/posts-by-tag posts)
        tags-file (fs/file tags-out-dir "index.html")
        template (base-html opts)
        stale? (or (lib/rendering-modified? rendering-system-files tags-out-dir)
                   (some :modified? posts)
                   force-render)]
    (when stale?
      (println "Writing tags page" (str tags-file))
      (lib/write-page! opts tags-file template
                       {:skip-archive true
                        :title (str blog-title " - Tags")
                        :relative-path "../"
                        :body (hiccup/html (lib/tag-links "Tags" posts-by-tag))})
      (doseq [tag-and-posts posts-by-tag]
        (lib/write-tag! opts tags-out-dir template tag-and-posts)))))

;;;; Generate index page with last 3 posts

(defn- index [{:keys [posts discuss-link templates-dir]}]
  (->> posts
       (map (fn [{:keys [file title date tags preview discuss]
                  :or {discuss discuss-link}
                  :as post}]
              (let [post-template (lib/ensure-resource (fs/file templates-dir "post.html"))]
                (->> (selmer/render (slurp post-template)
                                    (assoc post
                                           :post-link (str/replace file ".md" ".html")
                                           :body @(get @bodies file)))
                     (format "<div>\n%s\n</div>")))))
       (str/join "\n")))

(defn- spit-index
  [{:keys [blog-title num-index-posts out-dir posts force-render] :as opts}]
  (let [posts (take num-index-posts posts)
        out-file (fs/file out-dir "index.html")
        stale? (or (lib/rendering-modified? rendering-system-files out-file)
                   (some :modified? posts)
                   force-render)]
    (when stale?
      (let [body (index (assoc opts :posts posts))]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:title blog-title
                          :body body})))))

;;;; Generate archive page with links to all posts

(defn- spit-archive [{:keys [blog-title out-dir posts force-render] :as opts}]
  (let [out-file (fs/file out-dir "archive.html")
        stale? (or (lib/rendering-modified? rendering-system-files out-file)
                   (some :modified? posts)
                   force-render)]
    (when stale?
      (let [title (str blog-title " - Archive")]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:skip-archive true
                          :title title
                          :body (hiccup/html (lib/post-links "Archive" posts))})))))

;;;; Generate atom feeds

(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")
(import java.time.format.DateTimeFormatter)

(defn- rfc-3339-now []
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx")
        now (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)]
    (.format now fmt)))

(defn- rfc-3339 [yyyy-MM-dd]
  (let [in-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd")
        local-date (java.time.LocalDate/parse yyyy-MM-dd in-fmt)
        fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx")
        now (java.time.ZonedDateTime/of (.atTime local-date 23 59 59) java.time.ZoneOffset/UTC)]
    (.format now fmt)))

(defn- atom-feed
  ;; validate at https://validator.w3.org/feed/check.cgi
  [{:keys [posts blog-title blog-author blog-root]}]
  (-> (xml/sexp-as-element
       [::atom/feed
        {:xmlns "http://www.w3.org/2005/Atom"}
        [::atom/title blog-title]
        [::atom/link {:href (str blog-root "atom.xml") :rel "self"}]
        [::atom/link {:href blog-root}]
        [::atom/updated (rfc-3339-now)]
        [::atom/id blog-root]
        [::atom/author
         [::atom/name blog-author]]
        (for [{:keys [title date file preview]} posts
              :when (not preview)
              :let [html (str/replace file ".md" ".html")
                    link (str blog-root html)]]
          [::atom/entry
           [::atom/id link]
           [::atom/link {:href link}]
           [::atom/title title]
           [::atom/updated (rfc-3339 date)]
           [::atom/content {:type "html"}
            [:-cdata @(get @bodies file)]]])])
      xml/indent-str))

(defn- spit-feeds [{:keys [out-dir posts force-render]}]
  (let [feed-file (fs/file out-dir "atom.xml")
        clojure-feed-file (fs/file out-dir "planetclojure.xml")
        clojure-posts (filter
                       (fn [{:keys [tags]}]
                         (some tags ["clojure" "clojurescript"]))
                       posts)]
    (if (or (lib/rendering-modified? rendering-system-files clojure-feed-file)
            (some :modified? clojure-posts)
            force-render)
      (do
        (println "Writing Clojure feed" (str clojure-feed-file))
        (spit clojure-feed-file
              (atom-feed clojure-posts)))
      (println "No Clojure posts modified; skipping Clojure feed"))
    (if (or (lib/rendering-modified? rendering-system-files feed-file)
            (some :modified? posts)
            force-render)
      (do
        (println "Writing feed" (str feed-file))
        (spit feed-file
              (atom-feed posts)))
      (println "No posts modified; skipping main feed"))))

(defn render
  "Renders posts declared in `posts.edn` to `out-dir`."
  [opts]
  (let [{:keys [assets-dir
                assets-out-dir
                cache-dir
                default-metadata
                favicon-dir
                favicon-out-dir
                out-dir
                posts-dir
                posts-file
                templates-dir]
         :as opts} (apply-default-opts opts)
        posts (or (:posts opts)
                  (->> (lib/load-posts posts-dir default-metadata)
                       (lib/add-modified-metadata posts-dir out-dir)))
        opts (assoc opts :posts posts)
        assets-out-dir (fs/create-dirs assets-out-dir)]
    (when (empty? posts)
      (if (fs/exists? posts-file)
        (println (format "Run `bb migrate` to move metadata from `%s` to post files"
                         posts-file))
        (println "No posts found; run `bb new` to create one"))
      (System/exit 1))
    (reset! posts-cache posts)
    (lib/ensure-resource (fs/file templates-dir "style.css"))
    (ensure-favicon-assets opts)
    (when (fs/exists? assets-dir)
      (lib/copy-tree-modified assets-dir assets-out-dir))
    (when (fs/exists? favicon-dir)
      (lib/copy-tree-modified favicon-dir favicon-out-dir))
    (doseq [file (fs/glob templates-dir "*.{css,svg}")]
      (lib/copy-modified file (fs/file out-dir (.getFileName file))))
    (fs/create-dirs (fs/file cache-dir))
    (gen-posts opts)
    (gen-tags opts)
    (spit-archive opts)
    (spit-index opts)
    (spit-feeds opts)))

(defn quickblog
  "Alias for `render`"
  [opts]
  (render opts))

(defn- now []
  (.format (java.time.LocalDate/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")))

(defn new
  "Creates new `file` in posts dir."
  [{:keys [file title help
           posts-dir]
    :or {posts-dir (:posts-dir default-opts)}
    :as opts}]
  (let [_opts (assoc opts :posts-dir posts-dir)
        usage "Usage: bb new --file [POST_FILENAME] --title [POST_TITLE]"]
    (when help
      (println usage)
      (System/exit 0))
    (assert file (format "Missing required argument: --file\n\n%s" usage))
    (assert title (format "Missing required argument: --title\n\n%s" usage))
    (let [file (if (re-matches #"^.+[.][^.]+$" file)
                 file
                 (str file ".md"))
          post-file (fs/file posts-dir file)]
      (when-not (fs/exists? post-file)
        (fs/create-dirs posts-dir)
        (spit (fs/file posts-dir file)
              (format "Title: %s\nDate: %s\nTags: clojure\n\nWrite a blog post here!"
                      title (now)))))))

(defn delete
  "Deletes a post from the cache and output directories"
  [{:keys [file] :as opts}]
  (when-not file
    (println "Missing required argument: --file")
    (System/exit 1))
  (let [{:keys [cache-dir out-dir posts-dir]} (apply-default-opts opts)
        file (fs/file-name (fs/file file))]
    (fs/delete-if-exists (fs/file posts-dir file))
    (fs/delete-if-exists (fs/file cache-dir (lib/cache-file file)))
    (fs/delete-if-exists (fs/file out-dir (lib/html-file file)))
    ;; Remove post from tags, archive, index, and feeds by forcing re-render
    (render (assoc opts :force-render true))))

(defn migrate
  "Migrates from `posts.edn` to post-local metadata"
  [opts]
  (let [{:keys [posts-file] :as opts} (apply-default-opts opts)]
    (if (fs/exists? posts-file)
      (do
        (doseq [post (->> (slurp posts-file) (format "[%s]") edn/read-string)]
          (lib/migrate-post opts post))
        (println "If all posts were successfully migrated, you should now delete"
                 posts-file))
      (println (format "Posts file %s does not exist; no posts to migrate"
                       (str posts-file))))))

(defn serve
  "Runs file-server on `port`."
  [{:keys [port out-dir]
    :or {port 1888
         out-dir (:out-dir default-opts)}}]
  (let [serve (requiring-resolve 'babashka.http-server/serve)]
    (serve {:port port
            :dir out-dir})))

(defn- update-posts-cache! [opts path post-filename]
  (let [modified-post (lib/load-post path (:default-metadata opts))
        unmodify (fn [posts] (map #(assoc % :modified? false) posts))]
    (if modified-post
      (let [modified-post (assoc modified-post :modified? true)]
        (swap! posts-cache
               (fn [posts]
                 (if (some #(= post-filename (:file %)) posts)
                   (->> posts
                        unmodify
                        (map (fn [post]
                               (if (= post-filename (:file post))
                                 modified-post
                                 (assoc post :modified? false)))))
                   (->> (cons modified-post (unmodify posts))
                        lib/sort-posts)))))
      (swap! posts-cache (fn [posts]
                           (->> posts
                                unmodify
                                (remove #(= post-filename (:file %)))))))))

(defn watch
  "Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`."
  [{:keys [posts-dir templates-dir watch-script]
    :or {posts-dir (:posts-dir default-opts)
         templates-dir (:templates-dir default-opts)
         watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"}
    :as opts}]
  (let [opts (assoc opts
                    :watch watch-script
                    :posts-dir posts-dir)]
    (render opts)
    (serve opts)
    (let [load-pod (requiring-resolve 'babashka.pods/load-pod)]
      (load-pod 'org.babashka/fswatcher "0.0.3")
      (let [watch (requiring-resolve 'pod.babashka.fswatcher/watch)]
        (watch posts-dir
               (fn [{:keys [path type] :as event}]
                 (println "Change detected:" event)
                 (when (#{:create :remove :write|chmod} type)
                   (let [post-filename (-> (fs/file path) fs/file-name)]
                     ;; skip Emacs backup files and the like
                     (when-not (str/starts-with? post-filename ".")
                       (println "Re-rendering" post-filename)
                       (update-posts-cache! opts path post-filename)
                       (render (assoc opts :posts @posts-cache)))))))

        (watch templates-dir
               (fn [_]
                 (println "Re-rendering all posts")
                 (reset! posts-cache nil)
                 (render opts))))))
  @(promise))
