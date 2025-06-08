(ns commonmark.core-test
  "Tests for CommonMark parser implementation"
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [commonmark.core :as cm]))

(deftest test-basic-parsing
  (testing "Simple heading"
    (let [ast (cm/parse "# Hello World")]
      (is (= :document (:type ast)))
      (is (= 1 (count (:children ast))))
      (let [heading (first (:children ast))]
        (is (= :heading (:type heading)))
        (is (= 1 (:level heading))))))

  (testing "Paragraph with inline formatting"
    (let [ast (cm/parse "This is **bold** and *italic* text.")
          para (first (:children ast))
          children (:children para)]
      (is (= :paragraph (:type para)))
      (is (>= (count children) 5)) ; text, strong, text, emph, text (at least)
      (is (some #(= :strong (:type %)) children))
      (is (some #(= :emph (:type %)) children)))))

(deftest test-html-inline-parsing
  (testing "HTML inline tags"
    (let [ast (cm/parse "This has <em>HTML</em> tags.")
          para (first (:children ast))
          children (:children para)]
      (is (some #(= :html-inline (:type %)) children))))

  (testing "Mixed HTML and markdown"
    (let [ast (cm/parse "Text with <strong>HTML strong</strong> and **markdown strong**.")
          para (first (:children ast))
          children (:children para)]
      (is (some #(= :html-inline (:type %)) children)) ; HTML tags
      (is (some #(= :strong (:type %)) children))))) ; Markdown strong ; Markdown strong

(deftest test-lists
  (testing "Unordered list"
    (let [ast (cm/parse "- Item 1\n- Item 2\n- Item 3")
          list-node (first (:children ast))]
      (is (= :bullet-list (:type list-node)))
      (is (= 3 (count (:children list-node))))
      (is (every? #(= :list-item (:type %)) (:children list-node)))))

  (testing "Ordered list"
    (let [ast (cm/parse "1. First\n2. Second\n3. Third")
          list-node (first (:children ast))]
      (is (= :ordered-list (:type list-node)))
      (is (= 1 (:list-start list-node)))
      (is (= 3 (count (:children list-node))))))

  (testing "List with inline formatting"
    (let [ast (cm/parse "- Item with **bold**\n- Item with [link](http://example.com)")
          list-node (first (:children ast))
          first-item (first (:children list-node))
          second-item (second (:children list-node))]
      (is (some #(= :strong (:type %)) (:children first-item)))
      (is (some #(= :link (:type %)) (:children second-item))))))

(deftest test-blockquotes
  (testing "Simple blockquote"
    (let [ast (cm/parse "> This is a quote")
          quote (first (:children ast))]
      (is (= :blockquote (:type quote)))
      (is (= 1 (count (:children quote))))
      (is (= :paragraph (:type (first (:children quote)))))))

  (testing "Blockquote with formatting"
    (let [ast (cm/parse "> Quote with **bold** text")
          quote (first (:children ast))
          para (first (:children quote))]
      (is (some #(= :strong (:type %)) (:children para))))))

(deftest test-code-blocks
  (testing "Fenced code block"
    (let [ast (cm/parse "```clojure\n(def x 1)\n```")
          code-block (first (:children ast))]
      (is (= :code-block (:type code-block)))
      (is (= "clojure" (:info code-block)))
      (is (= "(def x 1)" (:literal code-block)))))

  (testing "Code block without language"
    (let [ast (cm/parse "```\nsome code\n```")
          code-block (first (:children ast))]
      (is (= :code-block (:type code-block)))
      (is (= "some code" (:literal code-block))))))

(deftest test-links
  (testing "Simple link"
    (let [ast (cm/parse "[Google](https://google.com)")
          para (first (:children ast))
          link (first (:children para))]
      (is (= :link (:type link)))
      (is (= "https://google.com" (:destination link)))
      (is (= "Google" (:literal (first (:children link)))))))

  (testing "Link with surrounding text"
    (let [ast (cm/parse "Visit [Google](https://google.com) for search.")
          para (first (:children ast))
          children (:children para)]
      (is (>= (count children) 3)) ; text, link, text (at least)
      (is (some #(= :link (:type %)) children)))))

(deftest test-html-rendering
  (testing "Render basic markdown structures"
    (let [ast (cm/parse "**Bold** and *italic* text.")
          html (cm/render-html ast)]
      (is (str/includes? html "<strong>"))
      (is (str/includes? html "<em>"))))

  (testing "Render HTML inline nodes"
    (let [ast (cm/parse "Text with <em>HTML</em> tags.")
          html (cm/render-html ast)]
      (is (str/includes? html "<em>"))
      (is (str/includes? html "</em>"))))

  (testing "Don't double-escape HTML nodes"
    (let [ast (cm/parse "Text with <strong>HTML strong</strong>.")
          html (cm/render-html ast)]
      ;; Should preserve the HTML tags as-is
      (is (str/includes? html "<strong>"))
      (is (str/includes? html "</strong>")))))

(deftest test-comprehensive-example
  (testing "Comprehensive document structure and HTML rendering"
    (let [markdown "# Document Title

This is a paragraph with **bold**, *italic*, and [link](http://example.com) text.

## Lists

- First item with <em>HTML</em>
- Second item with **markdown**
- Third item

1. Numbered item
2. Another numbered item

> A blockquote with **formatting**

```clojure
(defn hello [name]
  (println \"Hello\" name))
```

Final paragraph with <span>inline HTML</span>."
          ast (cm/parse markdown)]

      (testing "Document structure"
        (let [blocks (:children ast)]
          (is (> (count blocks) 5)) ; Multiple blocks

          ;; Check we have different block types
          (let [block-types (set (map :type blocks))]
            (is (contains? block-types :heading))
            (is (contains? block-types :paragraph))
            (is (contains? block-types :bullet-list))
            (is (contains? block-types :ordered-list))
            (is (contains? block-types :blockquote))
            (is (contains? block-types :code-block)))))

      (testing "HTML rendering produces valid output"
        (let [html (cm/render-html ast)]
          (is (str/includes? html "<h1>"))
          (is (str/includes? html "<ul>"))
          (is (str/includes? html "<ol>"))
          (is (str/includes? html "<blockquote>"))
          (is (str/includes? html "<pre><code")))))))

(deftest test-node-statistics
  (testing "Node statistics"
    (let [ast (cm/parse "# Title\n\nParagraph with **bold** and [link](url).\n\n- List item")
          stats-output (with-out-str (cm/stats ast))]
      (is (str/includes? stats-output ":document: 1"))
      (is (str/includes? stats-output ":heading"))
      (is (str/includes? stats-output ":paragraph"))
      (is (str/includes? stats-output ":strong"))
      (is (str/includes? stats-output ":link"))
      (is (str/includes? stats-output ":bullet-list")))))

;; Run the tests when this file is evaluated
(comment
  (run-tests))
