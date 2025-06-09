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
  (testing "Test parsing and rendering of thematic breaks (horizontal rules)"
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
        (is (every? #(= :thematic-break (:type %)) (:children ast)))))))

(deftest test-images
  (testing "Basic image syntax"
    (let [result (cm/render-html (cm/parse "![Alt text](https://example.com/image.jpg)"))]
      (is (= "<p><img src=\"https://example.com/image.jpg\" alt=\"Alt text\" /></p>" result))))

  (testing "Image with empty alt text"
    (let [result (cm/render-html (cm/parse "![](https://example.com/image.jpg)"))]
      (is (= "<p><img src=\"https://example.com/image.jpg\" alt=\"\" /></p>" result))))

  (testing "Image vs link distinction"
    (let [result (cm/render-html (cm/parse "![Image](pic.jpg) and [Link](page.html)"))]
      (is (= "<p><img src=\"pic.jpg\" alt=\"Image\" /> and <a href=\"page.html\">Link</a></p>" result))))

  (testing "Image in mixed content"
    (let [result (cm/render-html (cm/parse "Here is an image: ![Cat](cat.jpg) in the text."))]
      (is (= "<p>Here is an image: <img src=\"cat.jpg\" alt=\"Cat\" /> in the text.</p>" result))))

  (testing "Multiple images"
    (let [result (cm/render-html (cm/parse "![First](1.jpg) ![Second](2.jpg)"))]
      (is (= "<p><img src=\"1.jpg\" alt=\"First\" /><img src=\"2.jpg\" alt=\"Second\" /></p>" result))))

  (testing "Image with simple alt text"
    (let [result (cm/render-html (cm/parse "![Simple alt text](test.jpg)"))]
      (is (= "<p><img src=\"test.jpg\" alt=\"Simple alt text\" /></p>" result))))

  (testing "Image with URL containing special characters"
    (let [result (cm/render-html (cm/parse "![Test](https://example.com/path/image.jpg?param=value)"))]
      (is (= "<p><img src=\"https://example.com/path/image.jpg?param=value\" alt=\"Test\" /></p>" result)))))

(deftest test-hard-line-breaks
  (testing "Hard line break with two spaces"
    (is (= "<p>Line one<br />Line two</p>"
           (cm/render-html (cm/parse "Line one  \nLine two")))))

  (testing "Hard line break with multiple spaces"
    (is (= "<p>Line one<br />Line two</p>"
           (cm/render-html (cm/parse "Line one   \nLine two")))))

  (testing "Softbreak with just newline"
    (is (= "<p>Line one\nLine two</p>"
           (cm/render-html (cm/parse "Line one\nLine two")))))

  (testing "Multiple hard line breaks"
    (is (= "<p>Line one<br />Line two<br />Line three</p>"
           (cm/render-html (cm/parse "Line one  \nLine two  \nLine three")))))

  (testing "Mixed hard and soft breaks"
    (is (= "<p>Line one<br />Line two\nLine three</p>"
           (cm/render-html (cm/parse "Line one  \nLine two\nLine three")))))

  (testing "Hard line break at end of paragraph (should not create break)"
    (is (= "<p>Line one</p>"
           (cm/render-html (cm/parse "Line one  ")))))

  (testing "Hard line break with inline formatting"
    (is (= "<p><strong>Bold text</strong><br />Next line</p>"
           (cm/render-html (cm/parse "**Bold text**  \nNext line")))))

  (testing "Hard line break in list items"
    (is (= "<ul><li><p>Item one<br />Item continues</p></li></ul>"
           (cm/render-html (cm/parse "- Item one  \n  Item continues"))))))

(deftest test-setext-headings
  (testing "H1 setext heading with equals"
    (is (= "<h1>Heading 1</h1>"
           (cm/render-html (cm/parse "Heading 1\n=========")))))

  (testing "H2 setext heading with dashes"
    (is (= "<h2>Heading 2</h2>"
           (cm/render-html (cm/parse "Heading 2\n---------")))))

  (testing "Short underline still works"
    (is (= "<h1>Short</h1>"
           (cm/render-html (cm/parse "Short\n="))))
    (is (= "<h2>Also short</h2>"
           (cm/render-html (cm/parse "Also short\n-")))))

  (testing "Long underline works"
    (is (= "<h1>Heading</h1>"
           (cm/render-html (cm/parse "Heading\n==================")))))

  (testing "Multi-line paragraph does not become setext heading"
    (is (= "<p>Line one\nLine two\n=========</p>"
           (cm/render-html (cm/parse "Line one\nLine two\n=========")))))

  (testing "Setext heading with inline formatting"
    (is (= "<h1><strong>Bold heading</strong></h1>"
           (cm/render-html (cm/parse "**Bold heading**\n==============="))))
    (is (= "<h2><em>Italic</em> heading</h2>"
           (cm/render-html (cm/parse "*Italic* heading\n--------------")))))

  (testing "Setext heading in mixed content"
    (is (= "<p>Paragraph</p>\n<h1>Heading</h1>\n<p>Another paragraph</p>"
           (cm/render-html (cm/parse "Paragraph\n\nHeading\n=======\n\nAnother paragraph")))))

  (testing "Thematic break still works independently"
    (is (= "<hr />"
           (cm/render-html (cm/parse "---"))))
    (is (= "<p>Text</p>\n<hr />"
           (cm/render-html (cm/parse "Text\n\n---")))))

  (testing "Setext vs thematic break distinction"
    (is (= "<h2>Heading</h2>"
           (cm/render-html (cm/parse "Heading\n-------"))))
    (is (= "<hr />"
           (cm/render-html (cm/parse "-------"))))))

(deftest test-underscore-emphasis
  (testing "Basic underscore emphasis"
    (is (= "<p><em>italic</em> and <strong>bold</strong></p>"
           (cm/render-html (cm/parse "_italic_ and __bold__")))))

  (testing "Single underscore for emphasis"
    (is (= "<p><em>italic text</em></p>"
           (cm/render-html (cm/parse "_italic text_")))))

  (testing "Double underscore for strong emphasis"
    (is (= "<p><strong>bold text</strong></p>"
           (cm/render-html (cm/parse "__bold text__")))))

  (testing "Mixed asterisk and underscore emphasis"
    (is (= "<p><em>underscore</em> and <em>asterisk</em> and <strong>underscore</strong> and <strong>asterisk</strong></p>"
           (cm/render-html (cm/parse "_underscore_ and *asterisk* and __underscore__ and **asterisk**")))))

  (testing "Underscore emphasis with surrounding text"
    (is (= "<p>This is <em>italic</em> and this is <strong>bold</strong> text.</p>"
           (cm/render-html (cm/parse "This is _italic_ and this is __bold__ text.")))))

  (testing "Multiple underscore emphasis in same paragraph"
    (is (= "<p><em>first</em> and <em>second</em> and <strong>third</strong></p>"
           (cm/render-html (cm/parse "_first_ and _second_ and __third__")))))

  (testing "Underscore emphasis at start and end of paragraph"
    (is (= "<p><em>start</em> middle <strong>end</strong></p>"
           (cm/render-html (cm/parse "_start_ middle __end__")))))

  (testing "Underscore emphasis with punctuation"
    (is (= "<p><em>italic</em>, <strong>bold</strong>!</p>"
           (cm/render-html (cm/parse "_italic_, __bold__!")))))

  (testing "Underscore emphasis cannot be nested"
    ;; According to CommonMark, same delimiter types cannot be nested
    (is (= "<p><strong>bold _not nested_ bold</strong></p>"
           (cm/render-html (cm/parse "__bold _not nested_ bold__")))))

  (testing "Underscores in the middle of words don't create emphasis"
    (is (= "<p>snake_case_variable and file_name_here</p>"
           (cm/render-html (cm/parse "snake_case_variable and file_name_here")))))

  (testing "Underscores require word boundaries"
    (is (= "<p>a_b_c and <em>emphasized</em></p>"
           (cm/render-html (cm/parse "a_b_c and _emphasized_")))))

  (testing "Mixed with other inline formatting"
    (is (= "<p><em>italic</em> and <code>code</code> and <a href=\"url\">link</a></p>"
           (cm/render-html (cm/parse "_italic_ and `code` and [link](url)")))))

  (testing "Underscore emphasis in lists"
    (is (= "<ul><li><em>italic item</em></li><li><strong>bold item</strong></li></ul>"
           (cm/render-html (cm/parse "- _italic item_\n- __bold item__")))))

  (testing "Underscore emphasis in blockquotes"
    (is (= "<blockquote><p><em>italic quote</em> and <strong>bold quote</strong></p></blockquote>"
           (cm/render-html (cm/parse "> _italic quote_ and __bold quote__")))))

  (testing "Underscore emphasis in headings"
    (is (= "<h1><em>italic</em> heading</h1>"
           (cm/render-html (cm/parse "# _italic_ heading")))))

  (testing "Whitespace handling"
    ;; Emphasis markers cannot be followed by whitespace
    (is (= "<p>_ not italic _ and __ not bold __</p>"
           (cm/render-html (cm/parse "_ not italic _ and __ not bold __")))))

  (testing "Mismatched delimiters"
    (is (= "<p>_no match and __no match</p>"
           (cm/render-html (cm/parse "_no match and __no match")))))

  (testing "Empty emphasis"
    (is (= "<p>__ and ____</p>"
           (cm/render-html (cm/parse "__ and ____")))))

  (testing "Cross-delimiter nesting asterisk inside underscore"
    (is (= "<p><em>italic with <strong>bold</strong> inside</em></p>"
           (cm/render-html (cm/parse "_italic with **bold** inside_")))))

  (testing "Cross-delimiter nesting underscore inside asterisk"
    (is (= "<p><em>italic with <strong>bold</strong> inside</em></p>"
           (cm/render-html (cm/parse "*italic with __bold__ inside*")))))

  (testing "Triple underscore creates strong emphasis with leftover underscore"
    ;; ___text___ should be interpreted as __<em>text</em>__ which is <strong><em>text</em></strong>
    (is (= "<p><strong><em>text</em></strong></p>"
           (cm/render-html (cm/parse "___text___")))))

  (testing "Underscore emphasis with line breaks"
    (is (= "<p><em>multi\nline</em></p>"
           (cm/render-html (cm/parse "_multi\nline_")))))

  (testing "Underscore emphasis vs literal underscores in code"
    (is (= "<p><code>_not_emphasis_</code> but <em>this is</em></p>"
           (cm/render-html (cm/parse "`_not_emphasis_` but _this is_"))))))

;; Run the tests when this file is evaluated
(comment
  (run-tests))
