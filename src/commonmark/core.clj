(ns commonmark.core
  "A CommonMark parser implementation in Clojure"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; Node data structure using plain maps (more idiomatic Clojure)
;; Based on commonmark.js Node properties but only including non-nil values
;; Possible keys:
;;   :type - node type (:document, :paragraph, :text, :emph, :strong, etc.)
;;   :literal - string content for leaf nodes
;;   :destination - link/image destination
;;   :title - link/image title
;;   :info - code block info string
;;   :level - heading level
;;   :list-type - :bullet or :ordered
;;   :list-tight - boolean
;;   :list-start - starting number for ordered lists
;;   :list-delimiter - ")" or "."
;;   :source-pos - [[start-line start-col] [end-line end-col]]
;;   :children - vector of child nodes

;; Helper functions for creating nodes
(defn make-node
  "Create a node with the given type and optional attributes"
  ([type] (make-node type {}))
  ([type attrs]
   (merge {:type type
           :children []}
          attrs)))

(defn text-node
  "Create a text node with literal content"
  [content]
  {:type :text
   :literal content})

(defn document-node
  "Create a document root node"
  []
  {:type :document
   :children []})

(defn paragraph-node
  "Create a paragraph node"
  []
  {:type :paragraph
   :children []})

(defn emph-node
  "Create an emphasis node"
  []
  {:type :emph
   :children []})

(defn strong-node
  "Create a strong emphasis node"
  []
  {:type :strong
   :children []})

;; Node manipulation functions
(defn append-child
  "Add a child node to a parent node"
  [parent child]
  (update parent :children conj child))

(defn prepend-child
  "Add a child node to the beginning of a parent node's children"
  [parent child]
  (update parent :children #(into [child] %)))

(defn container?
  "Check if a node can contain children"
  [node]
  (not (#{:text :softbreak :linebreak :code :html-inline} (:type node))))

;; Simple tokenizer for basic parsing
(defn tokenize-line
  "Basic tokenizer that splits a line into tokens"
  [line]
  (when-not (empty? line)
    (->> line
         (re-seq #"\S+|\s+")
         (map str/trim)
         (filter #(not (empty? %))))))

;; Basic inline parser - handles emphasis for now
;; Inline parsing functions
;; Inline parsing functions (reorganized to fix forward references)
(declare parse-inline-text) ; Forward declaration

(defn parse-inline-complex
  "Parse text with mixed inline elements into AST nodes"
  [text]
  ;; Handle strong emphasis first (highest precedence)
  (if-let [match (re-find #"\*\*([^\*]+)\*\*" text)]
    (let [[full-match content] match
          idx (.indexOf text full-match)]
      (if (>= idx 0)
        (let [before (subs text 0 idx)
              after (subs text (+ idx (count full-match)))]
          (concat
           (if (not (str/blank? before)) (parse-inline-text before) [])
           [{:type :strong :children [(text-node content)]}]
           (if (not (str/blank? after)) (parse-inline-text after) [])))
        [(text-node text)]))

    ;; Handle emphasis
    (if-let [match (re-find #"(?<!\*)\*([^\*]+)\*(?!\*)" text)]
      (let [[full-match content] match
            idx (.indexOf text full-match)]
        (if (>= idx 0)
          (let [before (subs text 0 idx)
                after (subs text (+ idx (count full-match)))]
            (concat
             (if (not (str/blank? before)) (parse-inline-text before) [])
             [{:type :emph :children [(text-node content)]}]
             (if (not (str/blank? after)) (parse-inline-text after) [])))
          [(text-node text)]))

      ;; Handle links
      (if-let [match (re-find #"\[([^\]]+)\]\(([^\)]+)\)" text)]
        (let [[full-match link-text url] match
              idx (.indexOf text full-match)]
          (if (>= idx 0)
            (let [before (subs text 0 idx)
                  after (subs text (+ idx (count full-match)))]
              (concat
               (if (not (str/blank? before)) (parse-inline-text before) [])
               [{:type :link
                 :destination url
                 :children [(text-node link-text)]}]
               (if (not (str/blank? after)) (parse-inline-text after) [])))
            [(text-node text)]))

        ;; Default case - no inline formatting found
        [(text-node text)]))))

 ;; Now define the actual function
(defn parse-inline-text
  "Parse inline markdown into AST nodes (emphasis, strong, links, text)"
  [text]
  (if (str/blank? text)
    []
    ;; Simple implementation: handle the first occurrence of each pattern
    (cond
      ;; Handle strong emphasis first (**text**)
      (re-find #"\*\*([^\*]+)\*\*" text)
      (parse-inline-complex text)

      ;; Handle emphasis (*text*)
      (re-find #"(?<!\*)\*([^\*]+)\*(?!\*)" text)
      (parse-inline-complex text)

      ;; Handle links ([text](url))
      (re-find #"\[([^\]]+)\]\(([^\)]+)\)" text)
      (parse-inline-complex text)

      ;; No inline formatting - simple text node
      :else [(text-node text)])))

;; Simple block parser
(defn parse-paragraph
  "Parse consecutive non-empty lines as a paragraph with inline elements"
  [lines]
  (when (seq lines)
    (let [text (str/join " " lines)
          inline-nodes (parse-inline-text text)]
      (-> (paragraph-node)
          (update :children concat inline-nodes)))))

(defn blank-line? [line]
  (or (nil? line) (str/blank? line)))

(defn heading-line? [line]
  (and line (re-matches #"^#{1,6}\s+.*" line)))

(defn parse-heading
  "Parse ATX heading like '## Heading' with inline formatting"
  [line]
  (when-let [match (re-matches #"^(#{1,6})\s+(.*)" line)]
    (let [[_ hashes text] match
          level (count hashes)
          inline-nodes (parse-inline-text text)]
      (-> (make-node :heading {:level level})
          (update :children concat inline-nodes)))))

(defn blockquote-line? [line]
  (and line (str/starts-with? line ">")))

(defn code-fence-line? [line]
  (and line (re-matches #"^```.*" line)))

(defn parse-blockquote-line
  "Remove the > prefix from a blockquote line"
  [line]
  (-> line
      (str/replace #"^>\s?" "")
      str/trim))

(defn parse-blockquote
  "Parse consecutive > lines as a blockquote"
  [lines]
  (when (seq lines)
    (let [content (map parse-blockquote-line lines)
          text (str/join " " content)
          inline-nodes (parse-inline-text text)]
      (-> (make-node :blockquote)
          (append-child (-> (paragraph-node)
                            (update :children concat inline-nodes)))))))

(defn parse-code-block
  "Parse fenced code block"
  [lines]
  (when (seq lines)
    (let [first-line (first lines)
          last-line (last lines)
          content-lines (if (and (code-fence-line? first-line)
                                 (code-fence-line? last-line)
                                 (> (count lines) 2))
                          (drop 1 (drop-last 1 lines))
                          lines)
          content (str/join "\n" content-lines)
          info (when (code-fence-line? first-line)
                 (str/replace first-line #"^```" ""))]
      (make-node :code-block {:literal content :info info}))))

;; Main block parser
(defn parse-blocks
  "Parse lines into block-level elements"
  [lines]
  (loop [remaining lines
         blocks []
         current-para []
         current-blockquote []
         in-code-block false
         current-code []]
    (if (empty? remaining)
      ;; End of input - finish any current block
      (cond
        (seq current-code)
        (conj blocks (parse-code-block current-code))

        (seq current-blockquote)
        (conj blocks (parse-blockquote current-blockquote))

        (seq current-para)
        (conj blocks (parse-paragraph current-para))

        :else blocks)

      (let [line (first remaining)
            rest-lines (rest remaining)]

        (cond
          ;; Code fence - start or end
          (code-fence-line? line)
          (if in-code-block
            ;; End code block
            (recur rest-lines
                   (conj blocks (parse-code-block (conj current-code line)))
                   []
                   []
                   false
                   [])
            ;; Start code block - finish any current block first
            (let [new-blocks (cond
                               (seq current-blockquote)
                               (conj blocks (parse-blockquote current-blockquote))

                               (seq current-para)
                               (conj blocks (parse-paragraph current-para))

                               :else blocks)]
              (recur rest-lines
                     new-blocks
                     []
                     []
                     true
                     [line])))

          ;; Inside code block
          in-code-block
          (recur rest-lines blocks [] [] true (conj current-code line))

          ;; Heading
          (heading-line? line)
          (let [new-blocks (cond
                             (seq current-blockquote)
                             (conj blocks (parse-blockquote current-blockquote))

                             (seq current-para)
                             (conj blocks (parse-paragraph current-para))

                             :else blocks)]
            (recur rest-lines
                   (conj new-blocks (parse-heading line))
                   []
                   []
                   false
                   []))

          ;; Blockquote
          (blockquote-line? line)
          (let [new-blocks (if (seq current-para)
                             (conj blocks (parse-paragraph current-para))
                             blocks)]
            (recur rest-lines
                   new-blocks
                   []
                   (conj current-blockquote line)
                   false
                   []))

          ;; Blank line - end current block
          (blank-line? line)
          (cond
            (seq current-blockquote)
            (recur rest-lines
                   (conj blocks (parse-blockquote current-blockquote))
                   []
                   []
                   false
                   [])

            (seq current-para)
            (recur rest-lines
                   (conj blocks (parse-paragraph current-para))
                   []
                   []
                   false
                   [])

            :else
            (recur rest-lines blocks [] [] false []))

          ;; Regular line - add to current paragraph (if not in other block)
          :else
          (if (seq current-blockquote)
            ;; Continue blockquote with non-prefixed line
            (recur rest-lines
                   (conj blocks (parse-blockquote current-blockquote))
                   [line]
                   []
                   false
                   [])
            ;; Regular paragraph line
            (recur rest-lines
                   blocks
                   (conj current-para line)
                   []
                   false
                   [])))))))

;; Main parser
(defn parse
  "Parse markdown text into an AST"
  [markdown-text]
  (let [lines (str/split-lines markdown-text)
        blocks (parse-blocks lines)
        doc (document-node)]
    (reduce append-child doc blocks)))

;; Simple HTML renderer

(defn render-node
  "Render a single node to HTML"
  [node]
  (case (:type node)
    :document
    (str/join "\n" (map render-node (:children node)))

    :paragraph
    (str "<p>" (str/join "" (map render-node (:children node))) "</p>")

    :heading
    (let [level (:level node)
          tag (str "h" level)]
      (str "<" tag ">" (str/join "" (map render-node (:children node))) "</" tag ">"))

    :blockquote
    (str "<blockquote>" (str/join "" (map render-node (:children node))) "</blockquote>")

    :code-block
    (let [info (:info node)
          content (:literal node)]
      (if (and info (not (str/blank? info)))
        (str "<pre><code class=\"language-" info "\">" content "</code></pre>")
        (str "<pre><code>" content "</code></pre>")))

    :text
    ;; Text nodes contain plain text - no need for emphasis processing
    (:literal node)

    :emph
    (str "<em>" (str/join "" (map render-node (:children node))) "</em>")

    :strong
    (str "<strong>" (str/join "" (map render-node (:children node))) "</strong>")

    :link
    (let [url (:destination node)]
      (str "<a href=\"" url "\">" (str/join "" (map render-node (:children node))) "</a>"))

    ;; Default fallback
    (str "<!-- Unknown node type: " (:type node) " -->")))

(defn render-html
  "Render AST to HTML string"
  [ast]
  (render-node ast))

;; Debug helper to pretty print AST
(defn pprint-ast
  "Pretty print the AST structure"
  [node & {:keys [indent] :or {indent 0}}]
  (let [spaces (str/join (repeat indent "  "))]
    (print spaces)
    (println (str (:type node)
                  (when (:literal node) (str " \"" (:literal node) "\""))
                  (when (:level node) (str " level=" (:level node)))))
    (doseq [child (:children node)]
      (pprint-ast child :indent (inc indent)))))

;; Demo and testing functions
(defn demo
  "Demo the CommonMark parser with a comprehensive example"
  []
  (let [demo-md "# CommonMark Parser Demo

This is a **Clojure implementation** of a *CommonMark parser*.

## Features

The parser supports:

- ATX headings (like above)
- **Bold** and *italic* text
- [Links](https://commonmark.org) to external sites
- Blockquotes (see below)

> This is a blockquote with some **formatting** inside.
> It can span multiple lines.

### Code Support

Here's some Clojure code:

```clojure
(defn parse [markdown-text]
  (let [lines (str/split-lines markdown-text)
        blocks (parse-blocks lines)
        doc (document-node)]
    (reduce append-child doc blocks)))
```

## Conclusion

This parser demonstrates the **feasibility** of porting CommonMark to Clojure!"

        ast (parse demo-md)]

    (println "=== AST Structure ===")
    (pprint-ast ast)

    (println "\n=== HTML Output ===")
    (println (render-html ast))

    ast))

(defn stats
  "Show statistics about the parsed AST"
  [ast]
  (let [node-counts (atom {})]
    (walk/postwalk
     (fn [node]
       (when (and (map? node) (:type node))
         (swap! node-counts update (:type node) (fnil inc 0)))
       node)
     ast)

    (println "Node type counts:")
    (doseq [[type count] (sort @node-counts)]
      (println (str "  " type ": " count)))))

(comment
  (demo))
