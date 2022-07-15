(ns quickblog.api
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [selmer.parser :as selmer]))

(defmacro ^:private ->map [& ks]
  (assert (every? symbol? ks))
  (zipmap (map keyword ks)
          ks))

(def ^:private post-template
  "<h1>{{title}}</h1>
{{body | safe }}
<p>Discuss this post <a href=\"{{discuss}}\">here</a>.</p>
<p><i>Published: {{date}}</i></p>
")

;; re-used when generating atom.xml
(def ^:private bodies (atom {}))

(defn- html-file [file]
  (str/replace file ".md" ".html"))

(def ^:private discuss-fallback "https://github.com/borkdude/blog/discussions/categories/posts")

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

(defn- base-html []
  (ensure-template "templates/base.html")
  (slurp "templates/base.html"))

(defn- gen-posts [{:keys [posts out-dir] :as opts}]
  (doseq [{:keys [file title date legacy discuss]
           :or {discuss discuss-fallback}}
          posts]
    (let [base-html (base-html)
          cache-file (fs/file ".work" (html-file file))
          markdown-file (fs/file "posts" file)
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
            (spit (fs/file (fs/file legacy-dir "index.html")) redirect-html)))))))

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

(defn- index [{:keys [posts]}]
  (for [{:keys [file title date preview discuss]
         :or {discuss discuss-fallback}} (take 3 posts)
        :when (not preview)]
    [:div
     [:h1 [:a {:href (str/replace file ".md" ".html")}
           title]]
     (get @bodies file)
     [:p "Discuss this post " [:a {:href discuss} "here"] "."]
     [:p [:i "Published: " date]]]))

(defn- spit-index
  [{:keys [posts out-dir] :as opts}]
  (spit
   (fs/file out-dir "index.html")
   (selmer/render (base-html)
                  (assoc opts
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

(defn quickblog
  "Renders posts declared in `posts.edn` to `out-dir`."
  [{:keys [blog-title
           out-dir]
    :or {out-dir "public"}
    :as opts}]
  (ensure-template "templates/style.css")
  (let [opts (assoc opts :out-dir out-dir)
        posts (sort-by :date (comp - compare)
                       (edn/read-string (format "[%s]"
                                                (slurp "posts.edn"))))
        opts (assoc opts :posts posts)
        asset-dir (fs/create-dirs (fs/file out-dir "assets"))]
    (fs/copy-tree "assets" asset-dir {:replace-existing true})
    (doseq [file (fs/glob "templates" "*.{css,svg}")]
      (fs/copy file out-dir {:replace-existing true}))
    (fs/create-dirs (fs/file ".work"))
    (gen-posts opts)
    (spit (fs/file out-dir "archive.html")
          (selmer/render (base-html)
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

(defn- now []
  (pr-str
   (.format (java.time.LocalDate/now)
            (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))))

(defn new
  "Creates new entry in `posts.edn` and creates `file` in `posts` dir."
  [{:keys [file title]}]
  (assert file "Must give title")
  (assert title "Must give filename")
  (let [post-file (fs/file "posts" file)]
    (when-not (fs/exists? post-file)
      (spit (fs/file "posts" file) "TODO: write blog post")
      (spit (fs/file "posts.edn")
            (selmer/render post-template
                           {:title (pr-str title)
                            :file (pr-str file)
                            :date (now)
                            :categories #{"clojure"}})
            :append true))))

(defn serve
  "Runs file-server on `port`."
  [{:keys [port]
    :or {port 1888}}]
  (let [serve (requiring-resolve 'babashka.http-server/serve)]
    (serve {:port port
            :dir "public"})))

(defn watch
  "Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`."
  [{:keys [watch-script]
    :or {watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"}
    :as opts}]
  (let [opts (assoc opts :watch watch-script)]
    (quickblog opts)
    (serve opts)
    (let [load-pod (requiring-resolve 'babashka.pods/load-pod)]
      (load-pod 'org.babashka/filewatcher "0.0.1")
      (let [watch (requiring-resolve 'pod.babashka.filewatcher/watch)]
        (watch "posts.edn"
               (fn [_]
                 (println "Re-rendering")
                 (quickblog opts)))

        (watch "posts"
               (fn [_]
                 (println "Re-rendering")
                 (quickblog opts)))

        (watch "templates"
               (fn [_]
                 (println "Re-rendering")
                 (quickblog opts))))))
  @(promise))
