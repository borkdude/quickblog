(ns quickblog.api
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [quickblog.internal :as lib]
   [selmer.parser :as selmer]))

(defmacro ^:private ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

;; all values should be strings for consistency with command line args
(def ^:private default-opts
  {:assets-dir "assets"
   :cache-dir ".work"
   :favicon "false"
   :favicon-dir (fs/file "assets" "favicon")
   :num-index-posts 3
   :out-dir "public"
   :posts-dir "posts"
   :posts-file "posts.edn"
   :tags-dir "tags"
   :templates-dir "templates"})

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

;; re-used when generating atom.xml
(def ^:private bodies (atom {}))

(defn- html-file [file]
  (str/replace file ".md" ".html"))

(defn- markdown->html [file]
  (let [_ (println "Processing markdown for file:" (str file))
        markdown (slurp file)
        markdown (str/replace markdown #"--" (fn [_]
                                               "$$NDASH$$"))
        ;; allow links with markup over multiple lines
        markdown (str/replace markdown #"\[[^\]]+\n"
                              (fn [match]
                                (str/replace match "\n" "$$RET$$")))
        html (md/md-to-html-string markdown :code-style
                                   (fn [lang]
                                     (format "class=\"lang-%s\"" lang)))
        ;; see issue https://github.com/yogthos/markdown-clj/issues/146
        html (str/replace html "$$NDASH$$" "--")
        html (str/replace html "$$RET$$" "\n")]
    html))

(defn- base-html [{:keys [templates-dir]}]
  (let [template (fs/file templates-dir "base.html")]
    (lib/ensure-resource template)
    (slurp template)))

(defn- ensure-favicon-assets [{:keys [favicon-dir favicon]}]
  (when favicon
    (doseq [asset favicon-assets]
      (lib/ensure-resource (fs/file favicon-dir asset)))))

(defn- gen-posts [{:keys [posts discuss-link
                          cache-dir posts-dir out-dir templates-dir]
                   :as opts}]
  (let [post-template (fs/file templates-dir "post.html")]
    (fs/create-dirs cache-dir)
    (fs/create-dirs out-dir)
    (lib/ensure-resource post-template)
    (doseq [{:keys [file title date tags legacy discuss]
             :or {discuss discuss-link}}
            posts]
      (let [base-html (base-html opts)
            markdown-file (fs/file posts-dir file)
            cache-file (fs/file cache-dir (html-file file))
            out-file (fs/file out-dir (html-file file))
            stale? (seq (fs/modified-since cache-file markdown-file))
            body (if stale?
                   (let [body (markdown->html markdown-file)]
                     (spit cache-file body)
                     body)
                   (slurp cache-file))
            _ (swap! bodies assoc file body)
            body (selmer/render (slurp post-template)
                                (->map body title date tags discuss))]
        (when (or stale? (not (fs/exists? out-file)))
          (lib/write-page! opts out-file
                           base-html
                           {:title title
                            :body body}))
        (let [legacy-dir (fs/file out-dir (str/replace date "-" "/")
                                  (str/replace file ".md" ""))]
          (when legacy
            (fs/create-dirs legacy-dir)
            (let [redirect-html (selmer/render"
<html><head>
<meta http-equiv=\"refresh\" content=\"0; URL=/{{new_url}}\" />
</head></html>"
                                              {:new_url html-file})]
              (spit (fs/file (fs/file legacy-dir "index.html")) redirect-html))))))))

(defn- gen-tags [{:keys [posts posts-file blog-title out-dir tags-dir]
                  :as opts}]
  (let [tags-out-dir (fs/create-dirs (fs/file out-dir tags-dir))
        posts-by-tag (lib/posts-by-tag posts)
        tags-file (fs/file tags-out-dir "index.html")
        template (base-html opts)
        stale? (seq (fs/modified-since tags-out-dir posts-file))]
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

(defn- index [{:keys [posts discuss-link num-index-posts
                      templates-dir]}]
  (->> posts
       (remove :preview)
       (take num-index-posts)
       (map (fn [{:keys [file title date tags preview discuss]
                  :or {discuss discuss-link}
                  :as post}]
              (let [post-template (lib/ensure-resource (fs/file templates-dir "post.html"))]
                (->> (selmer/render (slurp post-template)
                                    (assoc post
                                           :post-link (str/replace file ".md" ".html")
                                           :body (get @bodies file)))
                     (format "<div>\n%s\n</div>")))))
       (str/join "\n")))

(defn- spit-index
  [{:keys [blog-title out-dir posts posts-file] :as opts}]
  (let [out-file (fs/file out-dir "index.html")
        stale? (seq (fs/modified-since out-file posts-file))]
    (when stale?
      (let [body (index (assoc opts :posts posts))]
        (lib/write-page! opts out-file
                         (base-html opts)
                         {:title blog-title
                          :body body})))))

;;;; Generate archive page with links to all posts

(defn- spit-archive [{:keys [blog-title out-dir posts posts-file] :as opts}]
  (let [out-file (fs/file out-dir "archive.html")
        stale? (seq (fs/modified-since out-file posts-file))]
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
  [{:keys [posts blog-title blog-root]}]
  (-> (xml/sexp-as-element
       [::atom/feed
        {:xmlns "http://www.w3.org/2005/Atom"}
        [::atom/title blog-title]
        [::atom/link {:href (str blog-root "atom.xml") :rel "self"}]
        [::atom/link {:href blog-root}]
        [::atom/updated (rfc-3339-now)]
        [::atom/id blog-root]
        [::atom/author
         [::atom/name "Michiel Borkent"]]
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
            [:-cdata (get @bodies file)]]])])
      xml/indent-str))

(defn render
  "Renders posts declared in `posts.edn` to `out-dir`."
  [{:keys [blog-title
           assets-dir
           cache-dir
           favicon
           favicon-dir
           num-index-posts
           out-dir
           posts-dir
           posts-file
           tags-dir
           templates-dir
           discuss-link]
    :or {assets-dir (:assets-dir default-opts)
         cache-dir (:cache-dir default-opts)
         favicon (:favicon default-opts)
         favicon-dir (:favicon-dir default-opts)
         num-index-posts (:num-index-posts default-opts)
         out-dir (:out-dir default-opts)
         posts-dir (:posts-dir default-opts)
         posts-file (:posts-file default-opts)
         tags-dir (:tags-dir default-opts)
         templates-dir (:templates-dir default-opts)}
    :as opts}]
  (lib/ensure-resource (fs/file templates-dir "style.css"))
  (let [opts (assoc opts
                    :out-dir out-dir
                    :assets-dir assets-dir
                    :cache-dir cache-dir
                    :discuss-link discuss-link
                    :favicon (and favicon
                                  (not= "false" favicon))
                    :favicon-dir favicon-dir
                    :num-index-posts num-index-posts
                    :posts-dir posts-dir
                    :posts-file posts-file
                    :tags-dir tags-dir
                    :templates-dir templates-dir)
        posts (lib/load-posts opts)
        opts (assoc opts :posts posts)
        asset-out-dir (fs/create-dirs (fs/file out-dir assets-dir))]
    (ensure-favicon-assets opts)
    (when (fs/exists? assets-dir)
      (lib/copy-tree-modified assets-dir asset-out-dir out-dir))
    (doseq [file (fs/glob templates-dir "*.{css,svg}")]
      (lib/copy-modified file (fs/file out-dir (.getFileName file))))
    (fs/create-dirs (fs/file cache-dir))
    (gen-posts opts)
    (gen-tags opts)
    (spit-archive opts)
    (spit-index opts)
    (spit (fs/file out-dir "atom.xml") (atom-feed opts))
    (spit (fs/file out-dir "planetclojure.xml")
          (atom-feed (filter
                      (fn [post]
                        (some (:tags post) ["clojure" "clojurescript"]))
                      posts)))))

(defn quickblog
  "Alias for `render`"
  [opts]
  (render opts))

(defn- now []
  (.format (java.time.LocalDate/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")))

(defn new
  "Creates new entry in `posts.edn` and creates `file` in `posts` dir."
  [{:keys [file title
           posts-dir]
    :or {posts-dir (:posts-dir default-opts)}
    :as opts}]
  (let [_opts (assoc opts :posts-dir posts-dir)]
    (assert file "Missing required argument: --file POST_FILENAME")
    (assert title "Missing required argument: --title POST_TITLE")
    (let [post-file (fs/file posts-dir file)]
      (when-not (fs/exists? post-file)
        (fs/create-dirs posts-dir)
        (spit (fs/file posts-dir file) "TODO: write blog post")
        (spit (fs/file "posts.edn")
              (with-out-str ((requiring-resolve 'clojure.pprint/pprint)
                             {:title title
                              :file file
                              :date (now)
                              :tags #{"clojure"}}))
              :append true)))))

(defn serve
  "Runs file-server on `port`."
  [{:keys [port out-dir]
    :or {port 1888
         out-dir (:out-dir default-opts)}}]
  (let [serve (requiring-resolve 'babashka.http-server/serve)]
    (serve {:port port
            :dir out-dir})))

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
        (watch "posts.edn"
               (fn [_]
                 (println "Re-rendering")
                 (render opts)))

        (watch posts-dir
               (fn [_]
                 (println "Re-rendering")
                 (render opts)))

        (watch templates-dir
               (fn [_]
                 (println "Re-rendering")
                 (render opts))))))
  @(promise))
