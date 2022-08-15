(ns quickblog.api-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [quickblog.api :as api])
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
  ([posts-dir {:keys [file title date tags content]
               :or {file "test.md"
                    title "Test post"
                    date "2022-01-02"
                    tags #{"clojure"}
                    content "Write a blog post here!"}}]
   (write-test-file posts-dir file
                    (format "Title: %s\nDate: %s\nTags: %s\n\n%s"
                            title date (str/join "," tags) content))))

(deftest new-test
  (with-dirs [posts-dir]
    (with-redefs [api/now (constantly "2022-01-02")]
      (api/new {:posts-dir posts-dir
                :file "test.md"
                :title "Test post"})
      (let [post-file (fs/file posts-dir "test.md")]
        (is (fs/exists? post-file))
        (is (= "Title: Test post\nDate: 2022-01-02\nTags: clojure\n\nWrite a blog post here!"
               (slurp post-file)))))))

(deftest migrate
  (with-dirs [posts-dir]
    (let [posts-edn (write-test-file posts-dir "posts.edn"
                                     {:file "test.md"
                                      :title "Test post"
                                      :date "2022-01-02"
                                      :tags #{"clojure"}})
          post-file (write-test-file posts-dir "test.md"
                                     "Write a blog post here!")]
      (api/migrate {:posts-dir posts-dir
                    :posts-file posts-edn})
      (is (= "Title: Test post\nDate: 2022-01-02\nTags: clojure\n\nWrite a blog post here!"
             (slurp post-file))))))

(deftest render
  (testing "happy path"
    (with-dirs [assets-dir
                posts-dir
                templates-dir
                cache-dir
                out-dir]
      (write-test-post posts-dir)
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
      (doseq [filename ["test.html" "index.html" "archive.html"
                        (fs/file "tags" "index.html")
                        (fs/file "tags" "clojure.html")
                        "atom.xml" "planetclojure.xml"]]
        (is (fs/exists? (fs/file out-dir filename))))))

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
                           "favicon-16x16.png"))))))

(deftest caching
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
        (let [asset-file (fs/file out-dir "assets" "asset.txt")]
          (let [mtime (fs/last-modified-time asset-file)]
            ;; Shouldn't copy unmodified file
            (render)
            (is (= mtime (fs/last-modified-time asset-file)))
            ;; Should copy modified file
            (write-test-file assets-dir "asset.txt" "something else")
            (render)
            (is (not= mtime (fs/last-modified-time asset-file))))))))

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
          (doseq [[filename mtime] content-cached]
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
          (is (not (fs/exists? (fs/file out-dir "tags" "clojure.html")))))))))
