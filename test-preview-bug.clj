#!/usr/bin/env bb

(require '[babashka.fs :as fs])
(require '[clojure.string :as str])

;; Test the preview tag caching bug
(defn test-preview-tag-bug []
  (let [test-dir ".test-preview-bug"
        posts-dir (fs/path test-dir "posts")
        out-dir (fs/path test-dir "out")
        cache-dir (fs/path test-dir "cache")]

    ;; Clean up any existing test directory
    (when (fs/exists? test-dir)
      (fs/delete-tree test-dir))

    ;; Create directories
    (fs/create-dirs posts-dir)

    ;; Create a test post
    (spit (fs/file posts-dir "test-post.md")
          "Title: Test Post
Date: 2023-01-01
Tags: clojure, testing

This is a test post.")

    ;; First render - post should appear in tag pages
    (println "First render - post should appear in tag pages")
    (clojure "-M:quickblog" "render"
             "--posts-dir" (str posts-dir)
             "--out-dir" (str out-dir)
             "--cache-dir" (str cache-dir))

    (let [tag-page (slurp (fs/file out-dir "tags" "clojure.html"))]
      (if (str/includes? tag-page "Test Post")
        (println "✓ Post appears in tag page")
        (println "✗ Post MISSING from tag page")))

    ;; Change to preview
    (spit (fs/file posts-dir "test-post.md")
          "Title: Test Post
Date: 2023-01-01
Tags: clojure, testing
Preview: true

This is a test post.")

    ;; Second render - post should NOT appear in tag pages
    (println "\nSecond render - post should NOT appear in tag pages (preview=true)")
    (clojure "-M:quickblog" "render"
             "--posts-dir" (str posts-dir)
             "--out-dir" (str out-dir)
             "--cache-dir" (str cache-dir))

    (let [tag-page (slurp (fs/file out-dir "tags" "clojure.html"))]
      (if (str/includes? tag-page "Test Post")
        (println "✗ BUG: Post still appears in tag page!")
        (println "✓ Post correctly removed from tag page")))

    ;; Clean up
    (fs/delete-tree test-dir)

    (println "\nTest complete.")))

(test-preview-tag-bug)
