(ns quickblog.api-test
  (:require
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [quickblog.api :as api]
   [quickblog.internal :as lib])
  (:import (java.util UUID)))

(def test-dir ".test")

(use-fixtures :each
  (fn [test-fn]
    (with-out-str
      (test-fn)
      (fs/delete-tree test-dir))))

(defn- tmp-dir [dir-name]
  (fs/file test-dir
           (format "quickblog-test-%s-%s" dir-name (str (UUID/randomUUID)))))

(defmacro with-dirs
  "dirs is a seq of directory names; e.g. [cache-dir out-dir]"
  [dirs & body]
  (let [binding-form# (mapcat (fn [dir] [dir `(tmp-dir ~(str dir))]) dirs)]
    `(let [~@binding-form#]
       ~@body)))

(defn- write-test-file [dir filename content]
  (fs/create-dirs dir)
  (let [f (fs/file dir filename)]
    (spit f content)
    f))

(defn- write-test-post
  ([posts-dir]
   (write-test-post posts-dir {}))
  ([posts-dir {:keys [file title date tags content preview?]
               :or {file "test.md"
                    title "Test post"
                    date "2022-01-02"
                    tags #{"clojure"}
                    content "Write a blog post here!"}}]
   (let [preview-str (if preview? "Preview: true\n" "")]
     (write-test-file posts-dir file
                      (format "Title: %s\nDate: %s\nTags: %s\n%s\n%s"
                              title date (str/join "," tags) preview-str content)))))

(deftest new-test
  (with-dirs [posts-dir]
    (with-redefs [api/now (constantly "2022-01-02")]
      (api/new {:posts-dir posts-dir
                :file "test.md"
                :title "Test post"
                :tags ["clojure" "some other tag"]})
      (let [post-file (fs/file posts-dir "test.md")]
        (is (fs/exists? post-file))
        (is (= "Title: Test post\nDate: 2022-01-02\nTags: clojure,some other tag\n\nWrite a blog post here!"
               (slurp post-file)))))))

(deftest migrate
  (with-dirs [posts-dir]
    (let [posts-edn (write-test-file posts-dir "posts.edn"
                                     {:file "test.md"
                                      :title "Test post"
                                      :date "2022-01-02"
                                      :tags #{"clojure"}})
          post-file (write-test-file posts-dir "test.md"
                                     "Write a blog post here!")
          to-lines #(-> % str/split-lines set)]
      (api/migrate {:posts-dir posts-dir
                    :posts-file posts-edn})
      (is (= (to-lines "Title: Test post\nDate: 2022-01-02\nTags: clojure\n\nWrite a blog post here!")
             (to-lines (slurp post-file)))))))

(deftest render
  (testing "happy path"
    (with-dirs [assets-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir {:tags #{"clojure" "tag with spaces"}})
      (write-test-post posts-dir {:file "preview.md"
                                  :title "This is a preview"
                                  :tags #{"preview"}
                                  :preview? true})
      (write-test-file assets-dir "asset.txt" "something")
      (api/render {:assets-dir assets-dir
                   :posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (fs/exists? (fs/file out-dir "assets" "asset.txt")))
      (doseq [filename ["base.html" "post.html" "style.css"]]
        (is (fs/exists? (fs/file templates-dir filename))))
      (is (fs/exists? (fs/file cache-dir "test.md.pre-template.html")))
      (is (fs/exists? (fs/file cache-dir "preview.md.pre-template.html")))
      (doseq [filename ["test.html" "preview.html" "index.html" "archive.html"
                        (fs/file "tags" "index.html")
                        (fs/file "tags" "clojure.html")
                        (fs/file "tags" "tag-with-spaces.html")
                        "atom.xml" "planetclojure.xml"]]
        (is (fs/exists? (fs/file out-dir filename))))
      (is (str/includes? (slurp (fs/file out-dir "test.html"))
                         "<a href=\"tags/tag-with-spaces.html\">tag with spaces</a>"))
      (is (str/includes? (slurp (fs/file out-dir "tags" "index.html"))
                         "<a href=\"tag-with-spaces.html\">tag with spaces</a>"))
      ;; Preview posts should be omitted from index, tags, and feeds
      (is (not (fs/exists? (fs/file out-dir "tags" "preview.html"))))
      (doseq [filename ["index.html" "atom.xml" "planetclojure.xml"]]
        (is (not (str/includes? (slurp (fs/file out-dir filename))
                                "preview.html"))))))

  (testing "with favicon"
    (with-dirs [favicon-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (let [favicon-out-dir (fs/file out-dir "favicon")]
        (write-test-post posts-dir)
        (api/render {:favicon true
                     :favicon-dir favicon-dir
                     :favicon-out-dir favicon-out-dir
                     :posts-dir posts-dir
                     :templates-dir templates-dir
                     :cache-dir cache-dir
                     :out-dir out-dir})
        (is (fs/exists? (fs/file templates-dir "favicon.html")))
        (doseq [filename (var-get #'api/favicon-assets)]
          (is (fs/exists? (fs/file favicon-dir filename)))
          (is (fs/exists? (fs/file favicon-out-dir filename))))
        (is (str/includes? (slurp (fs/file out-dir "index.html"))
                           "favicon-16x16.png")))))

  (testing "preview"
    (with-dirs [posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir {:file "preview.md"
                                  :content (str "always included\n\n"
                                                "<!-- end-of-preview -->\n\n"
                                                "only part of full post")})
      (api/render {:posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (str/includes? (slurp (fs/file out-dir "preview.html")) "<p>always included</p>"))
      (is (str/includes? (slurp (fs/file out-dir "preview.html")) "<p>only part of full post</p>"))
      (is (str/includes? (slurp (fs/file out-dir "index.html")) "<p>always included</p>"))
      (is (not (str/includes? (slurp (fs/file out-dir "index.html")) "<p>only part of full post</p>")))))
  
  (testing "multiline links"
    (with-dirs [posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir {:file "multiline.md"
                                  :content "[a \n\n multiline \n\n link](www.example.org)"})
      (api/render {:posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (str/includes? (slurp (fs/file out-dir "multiline.html"))
                         "<a href='www.example.org'>a \n\n multiline \n\n link</a>"))))

  (testing "tag with capitals"
    (with-dirs [assets-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir {:content "Post about ClojureScript"
                                  :tags #{"ClojureScript"}})
      (api/render {:assets-dir assets-dir
                   :posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (str/includes? (slurp (fs/file out-dir "planetclojure.xml")) "Post about ClojureScript"))))
  
    (testing "non-Clojure tag"
      (with-dirs [assets-dir
                  posts-dir
                  templates-dir
                  cache-dir
                  out-dir]
        (write-test-post posts-dir {:content "Post about Elixir"
                                    :tags #{"elixir"}})
        (api/render {:assets-dir assets-dir
                     :posts-dir posts-dir
                     :templates-dir templates-dir
                     :cache-dir cache-dir
                     :out-dir out-dir})
        (is (fs/exists? (fs/file out-dir "planetclojure.xml")))
        (is (not (str/includes? (slurp (fs/file out-dir "planetclojure.xml")) "Post about Elixir")))))

  (testing "comments"
    (with-dirs [posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir {:file "comments.md"
                                  :content "<!-- a comment -->"})
      (api/render {:posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (str/includes? (slurp (fs/file out-dir "comments.html")) "<!-- a comment -->"))))

  (testing "remove live reloading on render"
    (with-dirs [posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir)
      (api/render {:posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir
                   :watch lib/live-reload-script})
      (is (str/includes? (slurp (fs/file out-dir "test.html")) lib/live-reload-script))
      (api/render {:posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (is (not (str/includes? (slurp (fs/file out-dir "test.html")) lib/live-reload-script))))))

;; disabled, flaky in CI, cc @jmglov
#_(deftest caching
  (testing "assets"
    (with-dirs [assets-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (let [render #(api/render {:assets-dir assets-dir
                                 :posts-dir posts-dir
                                 :templates-dir templates-dir
                                 :cache-dir cache-dir
                                 :out-dir out-dir})]
        (write-test-post posts-dir)
        (write-test-file assets-dir "asset.txt" "something")
        (render)
        (let [asset-file (fs/file out-dir "assets" "asset.txt")
              mtime (fs/last-modified-time asset-file)]
          ;; Shouldn't copy unmodified file
          (render)
          (is (= mtime (fs/last-modified-time asset-file)))
          ;; Should copy modified file
          (write-test-file assets-dir "asset.txt" "something else")
          (render)
          (is (not= mtime (fs/last-modified-time asset-file)))))))

  (testing "posts"
    (with-dirs [posts-dir
                templates-dir
                cache-dir
                out-dir]
      (let [render #(api/render {:posts-dir posts-dir
                                 :templates-dir templates-dir
                                 :cache-dir cache-dir
                                 :out-dir out-dir})]
        (write-test-post posts-dir)
        (render)
        (let [->mtimes (fn [dir filenames]
                         (->> filenames
                              (map #(let [filename (fs/file dir %)]
                                      [filename (fs/last-modified-time filename)]))
                              (into {})))
              content-cached (merge (->mtimes cache-dir ["test.md.pre-template.html"])
                                    (->mtimes out-dir ["test.html" "index.html"
                                                       "atom.xml" "planetclojure.xml"]))
              metadata-cached (merge (->mtimes out-dir ["archive.html"])
                                     (->mtimes (fs/file out-dir "tags")
                                               ["index.html"]))
              clojure-metadata-cached (merge metadata-cached
                                             (->mtimes (fs/file out-dir "tags")
                                                       ["clojure.html"]))]
          ;; Shouldn't rewrite anything when post unmodified
          (render)
          (doseq [[filename mtime] (merge content-cached clojure-metadata-cached)]
            (is (= (map str [filename mtime])
                   (map str [filename (fs/last-modified-time filename)]))))
          ;; Should rewrite all but metadata-cached files when post modified
          (write-test-post posts-dir)
          (render)
          ;; disabled, flaky, /cc @jmglov
          #_(doseq [[filename mtime] content-cached]
            (is (not= (map str [filename mtime])
                      (map str [filename (fs/last-modified-time filename)]))))
          (doseq [[filename mtime] clojure-metadata-cached]
            (is (= (map str [filename mtime])
                   (map str [filename (fs/last-modified-time filename)]))))
          ;; Should rewrite everything when metadata modified
          (write-test-post posts-dir {:title "Changed", :tags #{"not-clojure"}})
          (render)
          (doseq [[filename mtime] (merge content-cached metadata-cached)]
            (is (not= (map str [filename mtime])
                      (map str [filename (fs/last-modified-time filename)]))))
          (is (fs/exists? (fs/file out-dir "tags" "not-clojure.html")))
          (is (not (fs/exists? (fs/file out-dir "tags" "clojure.html"))))))))

  (testing "feeds"
    (with-dirs [assets-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (let [render #(api/render {:assets-dir assets-dir
                                 :posts-dir posts-dir
                                 :templates-dir templates-dir
                                 :cache-dir cache-dir
                                 :out-dir out-dir})
            ->mtimes (fn [dir filenames]
                       (->> filenames
                            (map #(let [filename (fs/file dir %)]
                                    [filename (fs/last-modified-time filename)]))
                            (into {})))
            elem-tagged? (fn [tag el]
                           (let [tag (keyword (str "xmlns.http%3A%2F%2Fwww.w3.org%2F2005%2FAtom/" (name tag)))]
                             (and (instance? clojure.data.xml.node.Element el)
                                  (= tag (:tag el)))))
            post-ids (fn [filename]
                       (->> (xml/parse-str (slurp filename))
                            :content
                            (filter (partial elem-tagged? :entry))
                            (mapcat (fn [el]
                                      (->> (:content el)
                                           (filter (partial elem-tagged? :id))
                                           (map (comp #(str/replace % #".+/" "")
                                                      first
                                                      :content)))))
                            set))]
        (write-test-post posts-dir {:file "clojure1.md"
                                    :tags #{"clojure" "something"}})
        (write-test-post posts-dir {:file "clojurescript1.md"
                                    :tags #{"clojurescript" "something-else"}})
        (write-test-post posts-dir {:file "random1.md"
                                    :tags #{"something-else"}})
        (render)
        (is (= #{"clojure1.html"
                 "clojurescript1.html"
                 "random1.html"}
               (post-ids (fs/file out-dir "atom.xml"))))
        (is (= #{"clojure1.html"
                 "clojurescript1.html"}
               (post-ids (fs/file out-dir "planetclojure.xml"))))
        (write-test-post posts-dir {:file "clojure2.md"
                                    :tags #{"clojure"}})
        (write-test-post posts-dir {:file "random2.md"
                                    :tags #{"something"}})
        (let [mtimes (->mtimes out-dir ["atom.xml" "planetclojure.xml"])
              _ (render)
              mtimes-after (->mtimes out-dir ["atom.xml" "planetclojure.xml"])]
          (doseq [[filename mtime] mtimes-after]
            (is (not= [filename mtime] [filename (mtimes filename)]))))
        (is (= #{"clojure1.html"
                 "clojure2.html"
                 "clojurescript1.html"
                 "random1.html"
                 "random2.html"}
               (post-ids (fs/file out-dir "atom.xml"))))
        (is (= #{"clojure1.html"
                 "clojure2.html"
                 "clojurescript1.html"}
               (post-ids (fs/file out-dir "planetclojure.xml"))))
        (let [mtimes (->mtimes out-dir ["atom.xml" "planetclojure.xml"])
              _ (render)
              mtimes-after (->mtimes out-dir ["atom.xml" "planetclojure.xml"])]
          (doseq [[filename mtime] mtimes-after]
            (is (= [filename mtime] [filename (mtimes filename)]))))
        (is (= #{"clojure1.html"
                 "clojure2.html"
                 "clojurescript1.html"
                 "random1.html"
                 "random2.html"}
               (post-ids (fs/file out-dir "atom.xml"))))
        (is (= #{"clojure1.html"
                 "clojure2.html"
                 "clojurescript1.html"}
               (post-ids (fs/file out-dir "planetclojure.xml"))))))))

(defn- test-sharing [filename {:keys [title description image image-alt
                                      author twitter-handle]}]
  (let [meta (->> (slurp filename)
                  (re-seq #"<meta (?:name|property)=\"([^\"]+)\" content=\"([^\"]+)\">")
                  (map (fn [[_ k v]] [k v]))
                  (into {}))]
    (is (= "website" (meta "og:type")))
    (is (= "summary_large_image" (meta "twitter:card")))
    (is (= title (meta "title")))
    (is (= title (meta "og:title")))
    (is (= title (meta "twitter:title")))
    (is (= description (meta "description")))
    (is (= description (meta "og:description")))
    (is (= description (meta "twitter:description")))
    (is (= image (meta "og:image")))
    (is (= image (meta "twitter:image")))
    (is (= image-alt (meta "og:image:alt")))
    (is (= author (meta "twitter:creator")))
    (is (= twitter-handle (meta "twitter:site")))))

(deftest social-sharing
  (with-dirs [assets-dir
              posts-dir
              templates-dir
              cache-dir
              out-dir]
    (let [blog-title "quickblog"
          blog-description "A blog about blogging quickly"
          blog-root "http://localhost:1888"
          blog-image "assets/blog-preview.png"
          blog-image-alt "A shimmering sunset"
          twitter-handle "quickblogger"]
      (write-test-file posts-dir "test.md"
                       (str "Title: Test post\n"
                            "Date: 2022-01-02\n"
                            "Tags: clojure\n"
                            "Twitter-Handle: guestblogger\n"
                            "Description: Something or other\n"
                            "Image: assets/post-preview.png\n"
                            "Image-Alt: A leather-bound notebook lies open on a writing desk\n"
                            "\n"
                            "This is a test post"))
      (api/render {:blog-title blog-title
                   :blog-description blog-description
                   :blog-root blog-root
                   :blog-image blog-image
                   :blog-image-alt blog-image-alt
                   :twitter-handle twitter-handle
                   :assets-dir assets-dir
                   :posts-dir posts-dir
                   :templates-dir templates-dir
                   :cache-dir cache-dir
                   :out-dir out-dir})
      (test-sharing (fs/file out-dir "test.html")
                    {:title "Test post"
                     :description "Something or other"
                     :image "http://localhost:1888/assets/post-preview.png"
                     :image-alt "A leather-bound notebook lies open on a writing desk"
                     :author "guestblogger"
                     :twitter-handle "quickblogger"})
      (test-sharing (fs/file out-dir "index.html")
                    {:title blog-title
                     :description blog-description
                     :image (format "%s/%s" blog-root blog-image)
                     :image-alt blog-image-alt
                     :author twitter-handle
                     :twitter-handle twitter-handle})
      (test-sharing (fs/file out-dir "archive.html")
                    {:title (str blog-title " - Archive")
                     :description (str "Archive - " blog-description)
                     :image (format "%s/%s" blog-root blog-image)
                     :image-alt blog-image-alt
                     :author twitter-handle
                     :twitter-handle twitter-handle})
      (test-sharing (fs/file out-dir "tags" "index.html")
                    {:title (str blog-title " - Tags")
                     :description (str "Tags - " blog-description)
                     :image (format "%s/%s" blog-root blog-image)
                     :image-alt blog-image-alt
                     :author twitter-handle
                     :twitter-handle twitter-handle})
      (test-sharing (fs/file out-dir "tags" "clojure.html")
                    {:title (str blog-title " - Tag - clojure")
                     :description (str "Posts tagged &quot;clojure&quot; - " blog-description)
                     :image (format "%s/%s" blog-root blog-image)
                     :image-alt blog-image-alt
                     :author twitter-handle
                     :twitter-handle twitter-handle}))))

(deftest refresh-templates
  ;; This fails in CI, why? /cc @jmglov
  #_(with-dirs [templates-dir]
    (fs/create-dirs templates-dir)
    (let [default-templates ["base.html" "post.html" "favicon.html" "style.css"]
          custom-templates ["template1.html" "some-file.txt"]
          mtimes (->> (concat default-templates custom-templates)
                      (map #(let [filename %
                                  file (fs/file templates-dir filename)]
                              (spit file filename)
                              [filename (str (fs/last-modified-time file))]))
                      (into {}))]
      (api/refresh-templates {:templates-dir templates-dir})
      (doseq [filename default-templates
              :let [file (fs/file templates-dir filename)
                    mtime (str (fs/last-modified-time file))]]
        (is (not= [filename (mtimes filename)]
                  [filename mtime])))
      (doseq [filename custom-templates
              :let [file (fs/file templates-dir filename)
                    mtime (str (fs/last-modified-time file))]]
        (is (= [filename (mtimes filename)]
               [filename mtime]))))))
