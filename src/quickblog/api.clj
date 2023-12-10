(ns quickblog.api
  {:org.babashka/cli
   {:spec
    {
     ;; Blog metadata
     :blog-title
     {:desc "Title of the blog"
      :ref "<title>"
      :default "quickblog"
      :require true
      :group :blog-metadata}

     :blog-author
     {:desc "Author's name"
      :ref "<name>"
      :default "Quick Blogger"
      :require true
      :group :blog-metadata}

     :blog-description
     {:desc "Blog description for subtitle and RSS feeds"
      :ref "<text>"
      :default "A blog about blogging quickly"
      :require true
      :group :blog-metadata}

     :blog-root
     {:desc "Base URL of the blog"
      :ref "<url>"
      :default "https://github.com/borkdude/quickblog"
      :require true
      :group :blog-metadata}

     ;; Optional metadata
     :about-link
     {:desc "Link to about the author page"
      :ref "<url>"
      :group :optional-metadata}

     :discuss-link
     {:desc "Link to discussion forum for posts"
      :ref "<url>"
      :group :optional-metadata}

     :twitter-handle
     {:desc "Author's Twitter handle"
      :ref "<handle>"
      :group :optional-metadata}

     :page-suffix
     {:desc "Suffix to use for page links (default .html)"
      :ref "<suffix>"
      :default ".html"
      :group :optional-metadata}

     ;; Post config
     :default-metadata
     {:desc "Default metadata to add to posts"
      :default {:tags ["clojure"]}
      :group :post-config}

     :num-index-posts
     {:desc "Number of most recent posts to show on the index page"
      :ref "<num>"
      :default 3
      :group :post-config}

     :posts-file
     {:desc "File containing deprecated post metadata (used only for `migrate`)"
      :ref "<file>"
      :default "posts.edn"
      :group :post-config}

     ;; Input directories
     :assets-dir
     {:desc "Directory to copy assets (images, etc.) from"
      :ref "<dir>"
      :default "assets"
      :require true
      :group :input-directories}

     :posts-dir
     {:desc "Directory to read posts from"
      :ref "<dir>"
      :default "posts"
      :require true
      :group :input-directories}

     :templates-dir
     {:desc "Directory to read templates from; see Templates section in README"
      :ref "<dir>"
      :default "templates"
      :require true
      :group :input-directories}

     ;; Output directories
     :out-dir
     {:desc "Base directory for outputting static site"
      :ref "<dir>"
      :default "public"
      :require true
      :group :output-directories}

     :assets-out-dir
     {:desc "Directory to write assets to (relative to :out-dir)"
      :ref "<dir>"
      :default "assets"
      :require true
      :group :output-directories}

     :tags-dir
     {:desc "Directory to write tags to (relative to :out-dir)"
      :ref "<dir>"
      :default "tags"
      :require true
      :group :output-directories}

     ;; Caching
     :force-render
     {:desc "If true, pages will be re-rendered regardless of cache status"
      :default false
      :group :caching}

     :cache-dir
     {:desc "Directory to use for caching"
      :ref "<dir>"
      :default ".work"
      :require true
      :group :caching}

     :rendering-system-files
     {:desc "Files involved in rendering pages (only set if you know what you're doing!)"
      :ref "<file1> <file2>..."
      :default ["bb.edn" "deps.edn"]
      :coerce []
      :require true
      :group :caching}

     ;; Social sharing
     :blog-image
     {:desc "Blog thumbnail image URL; see Features > Social sharing in README"
      :ref "<url>"
      :group :social-sharing}

     ;; Favicon
     :favicon
     {:desc "If true, favicon will be added to all pages"
      :default false
      :group :favicon}

     :favicon-dir
     {:desc "Directory to read favicon assets from"
      :ref "<dir>"
      :default "assets/favicon"
      :group :favicon}

     :favicon-out-dir
     {:desc "Directory to write favicon assets to (relative to :out-dir)"
      :ref "<dir>"
      :default "assets/favicon"
      :group :favicon}

     ;; Command-specific opts

     }}}
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [quickblog.internal :as lib]
   [selmer.parser :as selmer]
   [selmer.filters :as filters]))

;; Add filter for tag page links; see:
;; https://github.com/yogthos/Selmer#filters
(filters/add-filter! :escape-tag lib/escape-tag)

(defn- update-out-dirs
  [{:keys [out-dir assets-out-dir favicon-out-dir] :as opts}]
  (let [out-dir-ify (fn [dir]
                      (if-not (str/starts-with? (str dir) (str out-dir))
                        (fs/file out-dir dir)
                        dir))]
    (assoc opts
           :assets-out-dir (out-dir-ify assets-out-dir)
           :favicon-out-dir (out-dir-ify favicon-out-dir))))

(defn- update-opts [opts]
  (-> opts
      (update :rendering-system-files #(map fs/file (cons (:templates-dir opts) %)))
      update-out-dirs))

(defn- get-defaults [metadata]
  (->> (get-in metadata [:org.babashka/cli :spec])
       (filter (fn [[_ m]] (contains? m :default)))
       (map (fn [[k m]] [k (:default m)]))
       (into {})))

(defn- apply-default-opts [opts]
  (let [defaults (get-defaults (meta (the-ns 'quickblog.api)))]
    (-> (->> defaults
             (map (fn [[k default]] [k (if (contains? opts k) (opts k) default)]))
             (into {}))
        (merge opts)
        update-opts)))

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
                          cache-dir out-dir]
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

(defn- gen-tags [{:keys [blog-title blog-description
                         blog-image blog-image-alt twitter-handle
                         modified-tags posts out-dir tags-dir]
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
                        :body (lib/tag-links "Tags" posts-by-tag opts)
                        :sharing {:description (format "Tags - %s"
                                                       blog-description)
                                  :author twitter-handle
                                  :twitter-handle twitter-handle
                                  :image (lib/blog-link opts blog-image)
                                  :image-alt blog-image-alt
                                  :url (lib/blog-link opts "tags/index.html")}})
      (doseq [tag-and-posts posts-by-tag]
        (lib/write-tag! opts tags-out-dir template tag-and-posts))
      ;; Delete tags pages for removed tags
      (doseq [tag (remove posts-by-tag modified-tags)
              :let [tag-filename (fs/file tags-out-dir (lib/tag-file tag))]]
        (println "Deleting removed tag:" (str tag-filename))
        (fs/delete-if-exists tag-filename)))))

;;;; Generate index page with last `num-index-posts` posts

(defn- index [{:keys [posts page-suffix] :as opts}]
  (let [posts (for [{:keys [file html] :as post} posts
                    :let [preview (first (str/split @html #"<!-- end-of-preview -->" 2))]]
                (assoc post
                       :post-link (str/replace file ".md" page-suffix)
                       :body preview
                       :truncated (not= preview @html)))
        index-template (lib/ensure-template opts "index.html")]
    (selmer/render (slurp index-template) (merge opts {:posts posts}))))

(defn- spit-index
  [{:keys [blog-title blog-description blog-image blog-image-alt twitter-handle
           posts cached-posts deleted-posts modified-posts num-index-posts
           out-dir]
    :as opts}]
  (let [index-posts #(->> (vals %)
                          lib/remove-previews
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
                          :sharing {:description blog-description
                                    :author twitter-handle
                                    :twitter-handle twitter-handle
                                    :image (lib/blog-link opts blog-image)
                                    :image-alt blog-image-alt
                                    :url (lib/blog-link opts "index.html")}})))))

;;;; Generate about page if template exists
(defn- about [{:keys [templates-dir] :as opts}]
  (selmer/render (slurp (fs/file templates-dir "about.html")) opts))

(defn- spit-about [{:keys [blog-title blog-description
                           blog-image blog-image-alt twitter-handle
                           modified-metadata out-dir]
                    :as opts}]
  (let [out-file (fs/file out-dir "about.html")
        stale? (or (some not-empty (vals modified-metadata))
                   (not (fs/exists? out-file)))]
    (when stale?
      (let [title (str blog-title " - About")]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:skip-archive true
                          :title title
                          :body (about opts)
                          :sharing {:description (format "About - %s"
                                                         blog-description)
                                    :author twitter-handle
                                    :twitter-handle twitter-handle
                                    :image (lib/blog-link opts blog-image)
                                    :image-alt blog-image-alt
                                    :url (lib/blog-link opts "about.html")}})))))

;;;; Generate archive page with links to all posts

(defn- spit-archive [{:keys [blog-title blog-description
                             blog-image blog-image-alt twitter-handle
                             modified-metadata posts out-dir]
                      :as opts}]
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
                          :body (lib/post-links "Archive" posts opts)
                          :sharing {:description (format "Archive - %s"
                                                         blog-description)
                                    :author twitter-handle
                                    :twitter-handle twitter-handle
                                    :image (lib/blog-link opts blog-image)
                                    :image-alt blog-image-alt
                                    :url (lib/blog-link opts "archive.html")}})))))

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
  [{:keys [blog-title blog-author blog-root page-suffix] :as opts} posts]
  (-> (xml/sexp-as-element
       [::atom/feed
        {:xmlns "http://www.w3.org/2005/Atom"}
        [::atom/title blog-title]
        [::atom/link {:href (lib/blog-link opts "atom.xml") :rel "self"}]
        [::atom/link {:href blog-root}]
        [::atom/updated (rfc-3339-now)]
        [::atom/id blog-root]
        [::atom/author
         [::atom/name blog-author]]
        (for [{:keys [title date file preview html]} posts
              :when (not preview)
              :let [html-file (str/replace file ".md" page-suffix)
                    link (lib/blog-link opts html-file)]]
          [::atom/entry
           [::atom/id link]
           [::atom/link {:href link}]
           [::atom/title title]
           [::atom/updated (rfc-3339 date)]
           [::atom/content {:type "html"}
            [:-cdata @html]]])])
      xml/indent-str))

(defn- clojure-post? [{:keys [tags]}]
  (let [clojure-tags #{"clojure" "clojurescript"}
        lowercase-tags (map str/lower-case tags)]
    (some clojure-tags lowercase-tags)))

(defn- spit-feeds [{:keys [out-dir modified-posts posts] :as opts}]
  (let [feed-file (fs/file out-dir "atom.xml")
        clojure-feed-file (fs/file out-dir "planetclojure.xml")
        all-posts (lib/sort-posts (vals posts))
        clojure-posts (->> (vals posts)
                           (filter clojure-post?)
                           lib/sort-posts)
        clojure-posts-modified? (->> modified-posts
                                     (map posts)
                                     (some clojure-post?))]
    (if (and (not clojure-posts-modified?) (fs/exists? clojure-feed-file))
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
    (if (empty? (:posts opts))
      (binding [*out* *err*]
        (println
         (if (fs/exists? posts-file)
           (format "Run `bb migrate` to move metadata from `%s` to post files"
                   posts-file)
           "No posts found; run `bb new` to create one")))
      (do
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
        (when (fs/exists? (fs/file templates-dir "about.html"))
          (spit-about opts))
        (spit-index opts)
        (spit-feeds opts)
        (lib/write-cache! opts)))
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
  {:org.babashka/cli
   {:spec
    {:file
     {:desc "Filename of post (relative to posts-dir)"
      :ref "<filename>"
      :require true}

     :preview
     {:desc "Create post as preview (won't be published to index, tags, or feeds)"
      :default false}

     :title
     {:desc "Title of post"
      :ref "<title>"
      :require true}

     :tags
     {:desc "List of tags (default: 'clojure'; example: --tags tag1 tag2 \"tag3 has spaces\")"
      :ref "<tags>"
      :coerce []}}}}
  [opts]
  (let [{:keys [file preview title posts-dir tags default-metadata]
         :as opts} (apply-default-opts opts)
        tags (cond (empty? tags)   (:tags default-metadata)
                   (= tags [true]) [] ;; `--tags` without arguments
                   :else tags)]
    (doseq [k [:file :title]]
      (assert (contains? opts k) (format "Missing required option: %s" k)))
    (let [file (if (re-matches #"^.+[.][^.]+$" file)
                 file
                 (str file ".md"))
          post-file (fs/file posts-dir file)
          preview-str (if preview "Preview: true\n" "")]
      (when-not (fs/exists? post-file)
        (fs/create-dirs posts-dir)
        (spit (fs/file posts-dir file)
              (format "Title: %s\nDate: %s\nTags: %s\n%s\nWrite a blog post here!"
                      title (now) (str/join "," tags) preview-str))))))

(defn clean
  "Removes cache and output directories"
  [opts]
  (let [{:keys [cache-dir out-dir]} (apply-default-opts opts)]
    (doseq [dir [cache-dir out-dir]]
      (println "Removing dir:" dir)
      (fs/delete-tree dir))))

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

(defn refresh-templates
  "Updates to latest default templates"
  [opts]
  (lib/refresh-templates (apply-default-opts opts)))

(defn serve
  "Runs file-server on `port`."
  {:org.babashka/cli
   {:spec
    {:port
     {:desc "Port for HTTP server to listen on"
      :ref "<port>"
      :default 1888}}}}
  ([opts] (serve opts true))
  ([opts block?]
   (let [{:keys [port out-dir]} (merge (get-defaults (meta #'serve))
                                       (apply-default-opts opts))
         serve (requiring-resolve 'babashka.http-server/serve)]
     (serve {:port port
             :dir out-dir})
     (when block? @(promise)))))

(def ^:private posts-cache (atom nil))

(defn watch
  "Watches posts, templates, and assets for changes. Runs file server using
  `serve`."
  {:org.babashka/cli
   {:spec
    {:port
     {:desc "Port for HTTP server to listen on"
      :ref "<port>"
      :default 1888}}}}
  [opts]
  (let [{:keys [assets-dir assets-out-dir posts-dir templates-dir]
         :as opts}
        (-> opts
            apply-default-opts
            (assoc :watch (format "<script type=\"text/javascript\" src=\"%s\"></script>"
                                  lib/live-reload-script))
            render)]
    (reset! posts-cache (:posts opts))
    (serve opts false)
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
                   (reset! posts-cache (:posts opts)))))

        (when (fs/exists? assets-dir)
          (watch assets-dir
                 (fn [{:keys [path type]}]
                   (println "Asset change detected:"
                            (name type) (str path))
                   (when (contains? #{:remove :rename} type)
                     (let [file (fs/file assets-out-dir (fs/file-name path))]
                       (println "Removing deleted asset:" (str file))
                       (fs/delete-if-exists file)))
                   (lib/copy-tree-modified assets-dir assets-out-dir)))))))
  @(promise))
