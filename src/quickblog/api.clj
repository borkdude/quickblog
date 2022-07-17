(ns quickblog.api
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [quickblog.internal :as lib]
   [selmer.parser :as selmer]))

(defmacro ^:private ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

(def ^:private default-opts
  {:assets-dir "assets"
   :cache-dir ".work"
   :out-dir "public"
   :posts-dir "posts"
   :templates-dir "templates"})

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

(defn- ensure-template [path]
  (let [f (fs/file path)]
    (when-not (fs/exists? f)
      (fs/create-dirs (fs/parent f))
      (spit f (slurp (io/resource path))))))

(defn- template [{:keys [templates-dir]} file-name]
  (let [template (fs/file templates-dir file-name)]
    (ensure-template template)
    (slurp template)))

(defn- base-html [opts]
  (template opts "base.html"))

(defn- post-html [opts]
  (template opts "post.html"))

(defn- gen-posts [{:keys [posts cache-dir posts-dir out-dir
                          discuss-link] :as opts}]
  (let [post-template (post-html opts)]
    (fs/create-dirs cache-dir)
    (fs/create-dirs out-dir)
    (doseq [{:keys [file title date legacy discuss]
             :or {discuss discuss-link}}
            posts]
      (let [base-html (base-html opts)
            cache-file (fs/file cache-dir (html-file file))
            markdown-file (fs/file posts-dir file)
            stale? (seq (fs/modified-since cache-file markdown-file))
            body (if stale?
                   (let [body (markdown->html markdown-file)]
                     (spit cache-file body)
                     body)
                   (slurp cache-file))
            _ (swap! bodies assoc file body)
            body (selmer/render post-template (->map body title date discuss))
            html (selmer/render base-html
                                (assoc opts
                                       :title title
                                       :body body))
            html-file (str/replace file ".md" ".html")]
        (spit (fs/file out-dir html-file) html)
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

;;;; Generate archive page

(defn- post-links [{:keys [posts]}]
  [:div {:style "width: 600px;"}
   [:h1 "Archive"]
   [:ul.index
    (for [{:keys [file title date preview]} posts
          :when (not preview)]
      [:li [:span
            [:a {:href (str/replace file ".md" ".html")}
             title]
            " - "
            date]])]])
;;;; Generate index page with last 3 posts

(defn- index [{:keys [posts discuss-link]}]
  (for [{:keys [file title date preview discuss]
         :or {discuss discuss-link}} (take 3 posts)
        :when (not preview)]
    [:div
     [:h1 [:a {:href (str/replace file ".md" ".html")}
           title]]
     (get @bodies file)
     [:p "Discuss this post " [:a {:href discuss} "here"] "."]
     [:p [:i "Published: " date]]]))

(defn- spit-index
  [{:keys [posts out-dir
           blog-title] :as opts}]
  (spit
   (fs/file out-dir "index.html")
   (selmer/render (base-html opts)
                  (assoc opts
                         :title blog-title
                         :body (hiccup/html {:escape-strings? false} (index {:posts posts}))))))

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
           out-dir
           posts-dir
           templates-dir
           discuss-link]
    :or {assets-dir (:assets-dir default-opts)
         cache-dir (:cache-dir default-opts)
         out-dir (:out-dir default-opts)
         posts-dir (:posts-dir default-opts)
         templates-dir (:templates-dir default-opts)}
    :as opts}]
  (ensure-template (fs/file templates-dir "style.css"))
  (let [opts (assoc opts
                    :out-dir out-dir
                    :assets-dir assets-dir
                    :cache-dir cache-dir
                    :posts-dir posts-dir
                    :templates-dir templates-dir
                    :discuss-link discuss-link)
        posts (sort-by :date (comp - compare)
                       (edn/read-string (format "[%s]"
                                                (slurp "posts.edn"))))
        opts (assoc opts :posts posts)
        asset-out-dir (fs/create-dirs (fs/file out-dir assets-dir))]
    (when (fs/exists? assets-dir)
      (lib/copy-tree-modified assets-dir asset-out-dir out-dir))
    (doseq [file (fs/glob templates-dir "*.{css,svg}")]
      (lib/copy-modified file (fs/file out-dir (.getFileName file))))
    (fs/create-dirs (fs/file cache-dir))
    (gen-posts opts)
    (spit (fs/file out-dir "archive.html")
          (selmer/render (base-html opts)
                         (assoc opts
                                :skip-archive true
                                :title (str blog-title " - Archive")
                                :body (hiccup/html (post-links {:posts posts})))))
    (spit-index opts)
    (spit (fs/file out-dir "atom.xml") (atom-feed opts))
    (spit (fs/file out-dir "planetclojure.xml")
          (atom-feed (filter
                      (fn [post]
                        (some (:categories post) ["clojure" "clojurescript"]))
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
                              :categories #{"clojure"}}))
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
