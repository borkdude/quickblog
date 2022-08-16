(ns quickblog.api
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.set :as set]
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
   :rendering-system-files ["bb.edn" "deps.edn" *file*]
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
  (let [opts (merge default-opts opts)]
    (-> opts
        (update :favicon #(and % (not= "false" %)))
        (update :force-render #(and % (not= "false" %)))
        (update :rendering-system-files #(map fs/file (cons (:templates-dir opts) %)))
        update-out-dirs)))

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

(def ^:private legacy-template "
<html><head>
<meta http-equiv=\"refresh\" content=\"0; URL=/{{new_url}}\" />
</head></html>")

(defn- base-html [opts]
  (slurp (lib/ensure-template opts "base.html")))

(defn- ensure-favicon-assets [{:keys [favicon favicon-dir]}]
  (when favicon
    (doseq [asset favicon-assets]
      (lib/ensure-resource (fs/file favicon-dir asset)
                           (fs/file "assets" "favicon" asset)))))

(defn- gen-posts [{:keys [deleted-posts modified-posts posts
                          cache-dir posts-dir out-dir templates-dir]
                   :as opts}]
  (let [posts-to-write (set/union modified-posts
                                  (lib/modified-post-pages opts))
        page-template (base-html opts)
        post-template (slurp (lib/ensure-template opts "post.html"))]
    (fs/create-dirs cache-dir)
    (fs/create-dirs out-dir)
    (doseq [[file post] posts
            :when (contains? posts-to-write file)
            :let [{:keys [file date legacy]} post
                  html-file (lib/html-file file)]]
      (lib/write-post! (assoc opts
                              :page-template page-template
                              :post-template post-template)
                       post)
      (let [legacy-dir (fs/file out-dir (str/replace date "-" "/")
                                (str/replace file ".md" ""))]
        (when legacy
          (fs/create-dirs legacy-dir)
          (let [legacy-file  (fs/file (fs/file legacy-dir "index.html"))
                redirect-html (selmer/render legacy-template
                                             {:new_url html-file})]
            (println "Writing legacy redirect:" (str legacy-file))
            (spit legacy-file redirect-html)))))
    (doseq [file deleted-posts]
      (println "Post deleted; removing from cache and outdir:" (str file))
      (fs/delete-if-exists (fs/file cache-dir (lib/cache-file file)))
      (fs/delete-if-exists (fs/file out-dir (lib/html-file file))))))

(defn- gen-tags [{:keys [blog-title blog-description modified-tags posts
                         out-dir tags-dir]
                  :as opts}]
  (let [tags-out-dir (fs/create-dirs (fs/file out-dir tags-dir))
        posts-by-tag (lib/posts-by-tag posts)
        tags-file (fs/file tags-out-dir "index.html")
        template (base-html opts)]
    (when (or (not (empty? modified-tags))
              (not (fs/exists? tags-file)))
      (lib/write-page! opts tags-file template
                       {:skip-archive true
                        :title (str blog-title " - Tags")
                        :relative-path "../"
                        :body (hiccup/html (lib/tag-links "Tags" posts-by-tag))
                        :sharing {:description (format "Tags - %s"
                                                       blog-description)}})
      (doseq [tag-and-posts posts-by-tag]
        (lib/write-tag! opts tags-out-dir template tag-and-posts))
      ;; Delete tags pages for removed tags
      (doseq [tag (remove posts-by-tag modified-tags)
              :let [tag-filename (fs/file tags-out-dir (lib/tag-file tag))]]
        (println "Deleting removed tag:" (str tag-filename))
        (fs/delete-if-exists tag-filename)))))

;;;; Generate index page with last 3 posts

(defn- index [{:keys [posts discuss-link templates-dir]}]
  (->> posts
       (map (fn [{:keys [file title date tags preview discuss html]
                  :or {discuss discuss-link}
                  :as post}]
              (let [post-template (lib/ensure-resource (fs/file templates-dir "post.html"))]
                (->> (selmer/render (slurp post-template)
                                    (assoc post
                                           :post-link (str/replace file ".md" ".html")
                                           :body @html))
                     (format "<div>\n%s\n</div>")))))
       (str/join "\n")))

(defn- spit-index
  [{:keys [blog-title blog-description
           posts cached-posts deleted-posts modified-posts num-index-posts
           out-dir]
    :as opts}]
  (let [index-posts #(->> (vals %)
                          lib/sort-posts
                          (take num-index-posts))
        posts (index-posts posts)
        cached-posts (index-posts cached-posts)
        out-file (fs/file out-dir "index.html")
        stale? (or (not= (map :file posts)
                         (map :file cached-posts))
                   (some modified-posts (map :file posts))
                   (some deleted-posts (map :file cached-posts))
                   (not (fs/exists? out-file)))]
    (when stale?
      (let [body (index (assoc opts :posts posts))]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:title blog-title
                          :body body
                          :sharing {:description blog-description}})))))

;;;; Generate archive page with links to all posts

(defn- spit-archive [{:keys [blog-title blog-description
                             modified-metadata posts out-dir] :as opts}]
  (let [out-file (fs/file out-dir "archive.html")
        stale? (or (some not-empty (vals modified-metadata))
                   (not (fs/exists? out-file)))]
    (when stale?
      (let [title (str blog-title " - Archive")
            posts (lib/sort-posts (vals posts))]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:skip-archive true
                          :title title
                          :body (hiccup/html (lib/post-links "Archive" posts))
                          :sharing {:description (format "Archive - %s"
                                                         blog-description)}})))))

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
  [{:keys [blog-title blog-author blog-root]} posts]
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
        (for [{:keys [title date file preview html]} posts
              :when (not preview)
              :let [html-file (str/replace file ".md" ".html")
                    link (str blog-root html-file)]]
          [::atom/entry
           [::atom/id link]
           [::atom/link {:href link}]
           [::atom/title title]
           [::atom/updated (rfc-3339 date)]
           [::atom/content {:type "html"}
            [:-cdata @html]]])])
      xml/indent-str))

(defn- spit-feeds [{:keys [out-dir modified-posts posts] :as opts}]
  (let [feed-file (fs/file out-dir "atom.xml")
        clojure-feed-file (fs/file out-dir "planetclojure.xml")
        clojure-posts (->> modified-posts
                           (map posts)
                           (filter (fn [{:keys [tags]}]
                                     (some tags ["clojure" "clojurescript"])))
                           lib/sort-posts)
        all-posts (lib/sort-posts (vals posts))]
    (if (and (empty? clojure-posts) (fs/exists? clojure-feed-file))
      (println "No Clojure posts modified; skipping Clojure feed")
      (do
        (println "Writing Clojure feed" (str clojure-feed-file))
        (spit clojure-feed-file (atom-feed opts clojure-posts))))
    (if (and (empty? modified-posts) (fs/exists? feed-file))
      (println "No posts modified; skipping main feed")
      (do
        (println "Writing feed" (str feed-file))
        (spit feed-file (atom-feed opts all-posts))))))

(defn render
  "Renders posts declared in `posts.edn` to `out-dir`."
  [opts]
  (let [{:keys [assets-dir
                assets-out-dir
                cache-dir
                favicon-dir
                favicon-out-dir
                out-dir
                posts-file
                templates-dir]
         :as opts}
        (-> opts apply-default-opts lib/refresh-cache)]
    (when (empty? (:posts opts))
      (if (fs/exists? posts-file)
        (println (format "Run `bb migrate` to move metadata from `%s` to post files"
                         posts-file))
        (println "No posts found; run `bb new` to create one"))
      (System/exit 1))
    (lib/ensure-template opts "style.css")
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
    (spit-feeds opts)
    (lib/write-cache! opts)
    opts))

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

(defn migrate
  "Migrates from `posts.edn` to post-local metadata"
  [opts]
  (let [{:keys [posts-file] :as opts} (apply-default-opts opts)]
    (if (fs/exists? posts-file)
      (do
        (doseq [post (->> (slurp posts-file) (format "[%s]") edn/read-string)]
          (lib/migrate-post opts post))
        (println "If all posts were successfully migrated, you should now delete"
                 (str posts-file)))
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

(def ^:private posts-cache (atom nil))

(defn watch
  "Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`."
  [{:keys [posts-dir templates-dir watch-script]
    :or {posts-dir (:posts-dir default-opts)
         templates-dir (:templates-dir default-opts)
         watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"}
    :as opts}]
  (let [opts (-> opts
                 (assoc :watch watch-script
                        :posts-dir posts-dir)
                 render)]
    (reset! posts-cache (:posts opts))
    (serve opts)
    (let [load-pod (requiring-resolve 'babashka.pods/load-pod)]
      (load-pod 'org.babashka/fswatcher "0.0.3")
      (let [watch (requiring-resolve 'pod.babashka.fswatcher/watch)]
        (watch posts-dir
               (fn [{:keys [path type]}]
                 (println "Change detected:" (name type) (str path))
                 (when (#{:create :remove :rename :write :write|chmod} type)
                   (let [post-filename (-> (fs/file path) fs/file-name)]
                     ;; skip Emacs backup files and the like
                     (when-not (str/starts-with? post-filename ".")
                       (println "Re-rendering" post-filename)
                       (let [post (lib/load-post opts path)
                             posts (cond
                                     (contains? #{:remove :rename} type)
                                     (dissoc @posts-cache post-filename)

                                     (:quickblog/error post)
                                     (do
                                       (println (:quickblog/error post))
                                       (dissoc @posts-cache post-filename))

                                     :else
                                     (assoc @posts-cache post-filename post))
                             opts (-> opts
                                      (assoc :cached-posts @posts-cache
                                             :posts posts)
                                      render)]
                         (reset! posts-cache (:posts opts))))))))

        (watch templates-dir
               (fn [{:keys [path type]}]
                 (println "Template change detected; re-rendering all posts:"
                          (name type) (str path))
                 (let [opts (-> opts
                                (dissoc :cached-posts :posts)
                                render)]
                   (reset! posts-cache (:posts opts))))))))
  @(promise))
