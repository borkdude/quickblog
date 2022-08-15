(ns quickblog.api-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [babashka.fs :as fs]
   [quickblog.api :as api])
  (:import (java.util UUID)))

(def test-dir ".test")

(use-fixtures :each (fn [test-fn] (test-fn) (fs/delete-tree test-dir)))

(defn- tmp-dir [dir-name]
  (fs/file test-dir
           (format "quickblog-test-%s-%s" dir-name (str (UUID/randomUUID)))))

(deftest new-test
  (let [posts-dir (tmp-dir "posts")]
    (with-redefs [api/now (constantly "2022-01-02")]
      (api/new {:posts-dir posts-dir
                :file "test.md"
                :title "Test post"})
      (let [post-file (fs/file posts-dir "test.md")]
        (is (fs/exists? post-file))
        (is (= "Title: Test post\nDate: 2022-01-02\nTags: clojure\n\nWrite a blog post here!"
               (slurp post-file)))))))
