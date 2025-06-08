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
      ;; With the new parsing, single-line items have inline content directly
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

(deftest test-complex-lists
  (testing "Ordered list with multi-paragraph items and code blocks"
    (let [markdown "1. List 1

   This is some text yo!
2. List 2

   This is still
   ```clojure
   Here!
   ```"
          ast (cm/parse markdown)]
      ;; Should be a single ordered list
      (is (= 1 (count (:children ast))))
      (let [list-node (first (:children ast))]
        (is (= :ordered-list (:type list-node)))
        (is (= 2 (count (:children list-node))))

        ;; First list item should have 2 children (2 paragraphs)
        (let [first-item (first (:children list-node))]
          (is (= :list-item (:type first-item)))
          (is (= 2 (count (:children first-item))))
          (is (every? #(= :paragraph (:type %)) (:children first-item))))

        ;; Second list item should have 3 children (2 paragraphs + 1 code block)
        (let [second-item (second (:children list-node))]
          (is (= :list-item (:type second-item)))
          (is (= 3 (count (:children second-item))))
          (let [child-types (map :type (:children second-item))]
            (is (= [:paragraph :paragraph :code-block] child-types))
            ;; Check code block content
            (let [code-block (nth (:children second-item) 2)]
              (is (= "clojure" (:info code-block)))
              (is (= "Here!" (:literal code-block)))))))))

  (testing "List continuation across blank lines"
    (let [markdown "1. First item

2. Second item
3. Third item"
          ast (cm/parse markdown)]
      ;; Should be a single ordered list with 3 items
      (is (= 1 (count (:children ast))))
      (let [list-node (first (:children ast))]
        (is (= :ordered-list (:type list-node)))
        (is (= 3 (count (:children list-node))))
        (is (every? #(= :list-item (:type %)) (:children list-node)))))))

(deftest test-inline-code
  (testing "Simple inline code"
    (let [ast (cm/parse "Hello `dude`")
          para (first (:children ast))
          children (:children para)]
      (is (= 2 (count children)))
      (is (= :text (:type (first children))))
      (is (= "Hello " (:literal (first children))))
      (is (= :code (:type (second children))))
      (is (= "dude" (:literal (second children))))))

  (testing "Mixed inline code and formatting"
    (let [ast (cm/parse "Hello `code` and **bold**")
          para (first (:children ast))
          children (:children para)]
      (is (= 4 (count children)))
      (is (= [:text :code :text :strong] (map :type children)))
      (is (= "code" (:literal (second children))))))

  (testing "Multiple code spans"
    (let [ast (cm/parse "Multiple `code` spans `in` one sentence")
          para (first (:children ast))
          children (:children para)]
      (is (= 5 (count children)))
      (is (= [:text :code :text :code :text] (map :type children)))
      (is (= "code" (:literal (second children))))
      (is (= "in" (:literal (nth children 3))))))

  (testing "Inline code HTML rendering"
    (let [ast (cm/parse "Hello `dude`")
          html (cm/render-html ast)]
      (is (str/includes? html "<code>dude</code>"))
      (is (str/includes? html "<p>Hello <code>dude</code></p>")))))

(deftest test-softbreaks
  (testing "Softbreak in list item"
    (let [ast (cm/parse "* dude `dude--dude`\n  whatever")
          list-item (first (:children (first (:children ast))))
          para (first (:children list-item))
          children (:children para)]
      (is (= 4 (count children)))
      (is (= [:text :code :softbreak :text] (map :type children)))
      (is (= "dude " (:literal (first children))))
      (is (= "dude--dude" (:literal (second children))))
      (is (= :softbreak (:type (nth children 2))))
      (is (= "whatever" (:literal (nth children 3))))))

  (testing "Multiple softbreaks"
    (let [ast (cm/parse "* line one\n  line two\n  line three")
          list-item (first (:children (first (:children ast))))
          para (first (:children list-item))
          children (:children para)]
      (is (= 5 (count children)))
      (is (= [:text :softbreak :text :softbreak :text] (map :type children)))
      (is (= "line one" (:literal (first children))))
      (is (= "line two" (:literal (nth children 2))))
      (is (= "line three" (:literal (nth children 4))))))

  (testing "Single line list item (no softbreak needed)"
    (let [ast (cm/parse "* single line")
          list-item (first (:children (first (:children ast))))
          children (:children list-item)]
      ;; Single line should not be wrapped in paragraph
      (is (= 1 (count children)))
      (is (= :text (:type (first children))))
      (is (= "single line" (:literal (first children))))))

  (testing "Softbreak HTML rendering"
    (let [ast (cm/parse "* dude `code`\n  whatever")
          html (cm/render-html ast)]
      (is (str/includes? html "<code>code</code>\nwhatever"))
      (is (str/includes? html "<li><p>dude <code>code</code>\nwhatever</p></li>")))))

(deftest test-autolinks
  (testing "Simple autolink"
    (let [ast (cm/parse "<https://www.example.com>")
          para (first (:children ast))
          children (:children para)]
      (is (= 1 (count children)))
      (is (= :autolink (:type (first children))))
      (is (= "https://www.example.com" (:destination (first children))))))

  (testing "Autolink with surrounding text"
    (let [ast (cm/parse "Visit <https://www.google.com> for search")
          para (first (:children ast))
          children (:children para)]
      (is (= 3 (count children)))
      (is (= [:text :autolink :text] (map :type children)))
      (is (= "Visit " (:literal (first children))))
      (is (= "https://www.google.com" (:destination (second children))))
      (is (= " for search" (:literal (nth children 2))))))

  (testing "Multiple autolinks"
    (let [ast (cm/parse "Check <http://example.com> and <https://other.com>")
          para (first (:children ast))
          children (:children para)]
      (is (= 4 (count children)))
      (is (= [:text :autolink :text :autolink] (map :type children)))
      (is (= "http://example.com" (:destination (second children))))
      (is (= "https://other.com" (:destination (nth children 3))))))

  (testing "Autolink vs HTML inline distinction"
    (let [ast (cm/parse "Link: <https://example.com> and HTML: <div>tag</div>")
          para (first (:children ast))
          children (:children para)]
      ;; Should have: text, autolink, text, html-inline, text, html-inline
      (is (= 6 (count children)))
      (is (= [:text :autolink :text :html-inline :text :html-inline] (map :type children)))
      (is (= :autolink (:type (second children))))
      (is (= :html-inline (:type (nth children 3))))
      (is (= :html-inline (:type (nth children 5))))))

  (testing "Autolink HTML rendering"
    (let [ast (cm/parse "<https://www.example.com>")
          html (cm/render-html ast)]
      (is (str/includes? html "<a href=\"https://www.example.com\">https://www.example.com</a>"))
      (is (str/includes? html "<p><a href=\"https://www.example.com\">https://www.example.com</a></p>"))))

  (testing "Mixed autolink and regular link"
    (let [ast (cm/parse "Auto: <https://example.com> and regular: [text](https://other.com)")
          para (first (:children ast))
          children (:children para)]
      (is (= 4 (count children)))
      (is (= [:text :autolink :text :link] (map :type children)))
      (is (= "https://example.com" (:destination (second children))))
      (is (= "https://other.com" (:destination (nth children 3)))))))

(deftest test-thematic-breaks
  "Test parsing and rendering of thematic breaks (horizontal rules)"
  (testing "thematic break detection"
    (is (cm/thematic-break-line? "***"))
    (is (cm/thematic-break-line? "---"))
    (is (cm/thematic-break-line? "- - -"))
    (is (cm/thematic-break-line? "* * *"))
    (is (cm/thematic-break-line? "___"))
    (is (cm/thematic-break-line? "-----"))
    (is (not (cm/thematic-break-line? "**")))
    (is (not (cm/thematic-break-line? "--")))
    (is (not (cm/thematic-break-line? "regular text")))
    (is (not (cm/thematic-break-line? "- List item"))))

  (testing "thematic break parsing"
    (is (= {:type :document, :children [{:type :thematic-break}]}
           (cm/parse "***")))
    (is (= {:type :document, :children [{:type :thematic-break}]}
           (cm/parse "---")))
    (is (= {:type :document, :children [{:type :thematic-break}]}
           (cm/parse "- - -"))))

  (testing "thematic break HTML rendering"
    (is (= "<hr />" (cm/render-html (cm/parse "***"))))
    (is (= "<hr />" (cm/render-html (cm/parse "---"))))
    (is (= "<hr />" (cm/render-html (cm/parse "- - -")))))

  (testing "thematic breaks don't interfere with lists"
    (let [ast (cm/parse "- List item 1\n- List item 2\n\n- - -\n\nAnother paragraph")]
      (is (= 3 (count (:children ast))))
      (is (= :bullet-list (:type (first (:children ast)))))
      (is (= :thematic-break (:type (second (:children ast)))))
      (is (= :paragraph (:type (nth (:children ast) 2))))))

  (testing "multiple thematic break styles"
    (let [ast (cm/parse "***\n\n---\n\n- - -\n\n_____")]
      (is (= 4 (count (:children ast))))
      (is (every? #(= :thematic-break (:type %)) (:children ast))))))

;; Run the tests when this file is evaluated
(comment
  (run-tests))
