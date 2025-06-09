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

(defn html-inline-node
  "Create an HTML inline node with literal content"
  [content]
  {:type :html-inline
   :literal content})

(defn code-node
  "Create an inline code node with literal content"
  [content]
  {:type :code
   :literal content})

(defn softbreak-node
  "Create a softbreak node (line break)"
  []
  {:type :softbreak})

(defn hardbreak-node
  "Create a hard line break node"
  []
  {:type :hardbreak})

(defn autolink-node
  "Create an autolink node with URL"
  [url]
  {:type :autolink
   :destination url
   :children [(text-node url)]})

(defn image-node
  "Create an image node with URL and alt text"
  ([alt url] (image-node alt url nil))
  ([alt url title]
   {:type :image
    :destination url
    :title title
    :children [(text-node alt)]}))

(defn thematic-break-node
  "Create a thematic break (horizontal rule) node"
  []
  {:type :thematic-break})

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
         (filter seq))))

;; Basic inline parser - handles emphasis for now
;; Inline parsing functions
;; Inline parsing functions (reorganized to fix forward references)
(declare parse-inline-text thematic-break-line?) ; Forward declaration

(defn parse-inline-complex
  "Parse text with mixed inline elements into AST nodes"
  [^String text]
  ;; Handle autolinks first (<url>)
  (if-let [match (re-find #"<(https?://[^>\s]+)>" text)]
    (let [[full-match url] match
          idx (.indexOf text ^String full-match)]
      (if (>= idx 0)
        (let [before (subs text 0 idx)
              after (subs text (+ idx (count full-match)))]
          (concat
           (if (not (str/blank? before)) (parse-inline-text before) [])
           [(autolink-node url)]
           (if (not (str/blank? after)) (parse-inline-text after) [])))
        [(text-node text)]))

    ;; Handle HTML inline tags (after autolinks)
    (if-let [match (re-find #"<[^>]+>" text)]
      (let [full-match match ; re-find returns the string directly for simple regex
            idx (.indexOf text ^String full-match)]
        (if (>= idx 0)
          (let [before (subs text 0 idx)
                after (subs text (+ idx (count full-match)))]
            (concat
             (if (not (str/blank? before)) (parse-inline-text before) [])
             [(html-inline-node full-match)]
             (if (not (str/blank? after)) (parse-inline-text after) [])))
          [(text-node text)]))

      ;; Handle inline code (`code`)
      (if-let [match (re-find #"`([^`]+)`" text)]
        (let [[full-match content] match
              idx (.indexOf text ^String full-match)]
          (if (>= idx 0)
            (let [before (subs text 0 idx)
                  after (subs text (+ idx (count full-match)))]
              (concat
               (if (not (str/blank? before)) (parse-inline-text before) [])
               [(code-node content)]
               (if (not (str/blank? after)) (parse-inline-text after) [])))
            [(text-node text)]))

        ;; Handle strong emphasis (**text**)
        (if-let [match (re-find #"\*\*([^\*]+)\*\*" text)]
          (let [[full-match content] match
                idx (.indexOf text ^String full-match)]
            (if (>= idx 0)
              (let [before (subs text 0 idx)
                    after (subs text (+ idx (count full-match)))]
                (concat
                 (if (not (str/blank? before)) (parse-inline-text before) [])
                 [{:type :strong :children [(text-node content)]}]
                 (if (not (str/blank? after)) (parse-inline-text after) [])))
              [(text-node text)]))

          ;; Handle emphasis (*text*)
          (if-let [match (re-find #"(?<!\*)\*([^\*]+)\*(?!\*)" text)]
            (let [[full-match content] match
                  idx (.indexOf text ^String full-match)]
              (if (>= idx 0)
                (let [before (subs text 0 idx)
                      after (subs text (+ idx (count full-match)))]
                  (concat
                   (if (not (str/blank? before)) (parse-inline-text before) [])
                   [{:type :emph :children [(text-node content)]}]
                   (if (not (str/blank? after)) (parse-inline-text after) [])))
                [(text-node text)]))

            ;; Handle images (![alt](url)) - must come before links
            (if-let [match (re-find #"!\[([^\]]*)\]\(([^\)]+)\)" text)]
              (let [[full-match alt-text url] match
                    idx (.indexOf text ^String full-match)]
                (if (>= idx 0)
                  (let [before (subs text 0 idx)
                        after (subs text (+ idx (count full-match)))]
                    (concat
                     (if (not (str/blank? before)) (parse-inline-text before) [])
                     [(image-node alt-text url)]
                     (if (not (str/blank? after)) (parse-inline-text after) [])))
                  [(text-node text)]))

              ;; Handle links ([text](url))
              (if-let [match (re-find #"\[([^\]]+)\]\(([^\)]+)\)" text)]
                (let [[full-match link-text url] match
                      idx (.indexOf text ^String full-match)]
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
                [(text-node text)]))))))))

 ;; Now define the actual function
(defn parse-inline-text
  "Parse inline markdown into AST nodes (autolinks, HTML inline, code, emphasis, strong, images, links, text)"
  [^String text]
  (if (str/blank? text)
    []
    ;; Simple implementation: handle the first occurrence of each pattern
    (cond
      ;; Handle autolinks first (<url>)
      (re-find #"<(https?://[^>\s]+)>" text)
      (parse-inline-complex text)

      ;; Handle HTML inline tags (after autolinks)
      (re-find #"<[^>]+>" text)
      (parse-inline-complex text)

      ;; Handle inline code (`code`)
      (re-find #"`([^`]+)`" text)
      (parse-inline-complex text)

      ;; Handle strong emphasis (**text**)
      (re-find #"\*\*([^\*]+)\*\*" text)
      (parse-inline-complex text)

      ;; Handle emphasis (*text*)
      (re-find #"(?<!\*)\*([^\*]+)\*(?!\*)" text)
      (parse-inline-complex text)

      ;; Handle images (![alt](url)) - must come before links
      (re-find #"!\[([^\]]*)\]\(([^\)]+)\)" text)
      (parse-inline-complex text)

      ;; Handle links ([text](url))
      (re-find #"\[([^\]]+)\]\(([^\)]+)\)" text)
      (parse-inline-complex text)

      ;; No inline formatting - simple text node
      :else [(text-node text)])))

;; Simple block parser
(defn parse-paragraph
  "Parse consecutive non-empty lines as a paragraph with inline elements, handling hard line breaks"
  [lines]
  (when (seq lines)
    (if (= 1 (count lines))
      ;; Single line - simple inline parsing (remove trailing spaces since no line break follows)
      (let [clean-line (str/replace (first lines) #"\s+$" "")
            inline-nodes (parse-inline-text clean-line)]
        (-> (paragraph-node)
            (update :children concat inline-nodes)))
      ;; Multiple lines - check for hard line breaks
      (let [inline-nodes (parse-inline-with-softbreaks lines)]
        (-> (paragraph-node)
            (update :children concat inline-nodes))))))

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

(defn list-item-line?
  "Check if a line is a list item (ordered or unordered), but not a thematic break"
  [line]
  (and line
       ;; First check it's not a thematic break
       (not (thematic-break-line? line))
       ;; Then check if it matches list patterns
       (or (re-matches #"^\s*[-*+]\s+.*" line) ; unordered list
           (re-matches #"^\s*\d+\.\s+.*" line))))

(defn indented-content-line?
  "Check if a line is indented content (part of a list item)"
  [line base-indent]
  (and line
       (not (str/blank? line))
       (not (list-item-line? line))
       (let [line-indent (count (take-while #(= % \space) line))]
         (>= line-indent base-indent))))

(defn get-line-indent
  "Get the indentation level of a line"
  [line]
  (if (str/blank? line)
    0
    (count (take-while #(= % \space) line)))) ; ordered list

(defn parse-list-item-line
  "Parse a list item line and return content with indentation info"
  [line]
  (cond
    ;; Unordered list item
    (re-matches #"^\s*[-*+]\s+.*" line)
    (let [match (re-find #"^(\s*)([-*+])\s+(.*)" line)]
      (when match
        {:type :bullet
         :indent (count (nth match 1))
         :marker (nth match 2)
         :content (nth match 3)}))

    ;; Ordered list item
    (re-matches #"^\s*\d+\.\s+.*" line)
    (let [match (re-find #"^(\s*)(\d+)\.\s+(.*)" line)]
      (when match
        {:type :ordered
         :indent (count (nth match 1))
         :start (Integer/parseInt (nth match 2))
         :content (nth match 3)}))

    :else nil))

(defn parse-inline-with-softbreaks
  "Parse multiple lines as inline content with hard breaks and softbreaks between lines"
  [lines]
  (when (seq lines)
    (loop [remaining lines
           nodes []]
      (if (empty? remaining)
        nodes
        (let [line (first remaining)
              rest-lines (rest remaining)
              ;; Check if line ends with two or more spaces (hard line break)
              has-hard-break? (and (seq rest-lines) (re-find #"  +$" line))
              ;; Remove trailing spaces from line for processing
              clean-line (str/replace line #"\s+$" "")
              inline-nodes (parse-inline-text clean-line)]
          (if (seq rest-lines)
            ;; More lines remaining - add inline nodes + appropriate break
            (recur rest-lines
                   (concat nodes
                           inline-nodes
                           [(if has-hard-break? (hardbreak-node) (softbreak-node))]))
            ;; Last line - just add inline nodes (no trailing spaces matter)
            (concat nodes inline-nodes)))))))

(defn parse-list-item-content
  "Parse the content of a list item, which can include multiple blocks"
  [content-lines]
  (when (seq content-lines)
    (cond
      ;; Single line - create inline nodes directly
      (= 1 (count content-lines))
      (parse-inline-text (first content-lines))

      ;; Multiple lines without blank lines - single paragraph with softbreaks
      (and (> (count content-lines) 1)
           (not-any? str/blank? content-lines))
      (let [inline-nodes (parse-inline-with-softbreaks content-lines)]
        [(-> (paragraph-node)
             (update :children concat inline-nodes))])

      ;; Multiple lines with possible blank lines - need to group into blocks
      :else
      (loop [remaining content-lines
             blocks []
             current-para []
             in-code-block false
             current-code []]
        (if (empty? remaining)
          ;; End of content - finish any current block
          (cond
            (seq current-code)
            (conj blocks (parse-code-block current-code))

            (seq current-para)
            (conj blocks (-> (paragraph-node)
                             (update :children concat (parse-inline-with-softbreaks current-para))))

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
                       false
                       [])
                ;; Start code block - finish any current paragraph first
                (let [new-blocks (if (seq current-para)
                                   (conj blocks (-> (paragraph-node)
                                                    (update :children concat (parse-inline-with-softbreaks current-para))))
                                   blocks)]
                  (recur rest-lines
                         new-blocks
                         []
                         true
                         [line])))

              ;; Inside code block
              in-code-block
              (recur rest-lines blocks [] true (conj current-code line))

              ;; Blank line - end current paragraph
              (str/blank? line)
              (if (seq current-para)
                (recur rest-lines
                       (conj blocks (-> (paragraph-node)
                                        (update :children concat (parse-inline-with-softbreaks current-para))))
                       []
                       false
                       [])
                (recur rest-lines blocks [] false []))

              ;; Regular line - add to current paragraph
              :else
              (recur rest-lines
                     blocks
                     (conj current-para line)
                     false
                     []))))))))

(defn collect-list-item-lines
  "Collect all lines that belong to a single list item, including indented content"
  [lines item-info]
  (let [marker-indent (:indent item-info)
        ; Calculate content indentation - for "1. " it would be marker-indent + 3
        content-indent (+ marker-indent
                          (if (= :ordered (:type item-info))
                            (+ (count (str (:start item-info))) 2) ; "1. " = 3 chars
                            2))] ; "- " = 2 chars  
    (loop [remaining (rest lines) ; skip the list item line itself
           collected [(:content item-info)] ; start with the list item content
           blank-line-buffer []]
      (if (empty? remaining)
        collected
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (cond
            ;; Another list item - stop collecting
            (list-item-line? line)
            collected

            ;; Blank line - buffer it in case more content follows
            (str/blank? line)
            (recur rest-lines collected (conj blank-line-buffer line))

            ;; Indented content - belongs to this list item
            (>= (get-line-indent line) content-indent)
            (let [trimmed-line (subs line (min content-indent (count line)))] ; remove indentation
              (recur rest-lines
                     (concat collected blank-line-buffer [trimmed-line])
                     []))

            ;; Non-indented content - end of list item
            :else
            collected))))))

(defn parse-advanced-list-items
  "Parse list items with proper support for multi-block content"
  [lines]
  (when (seq lines)
    (loop [remaining lines
           items []]
      (if (empty? remaining)
        items
        (let [line (first remaining)]
          (if (list-item-line? line)
            (let [item-info (parse-list-item-line line)
                  item-content-lines (collect-list-item-lines remaining item-info)
                  item-blocks (parse-list-item-content item-content-lines)
                  list-item (make-node :list-item {:children item-blocks})
                  ;; collect-list-item-lines processes content after the list item line
                  ;; so we need to skip 1 line (the list item line) plus the content lines
                  ;; But actually, let's just move to the next list item 
                  next-list-item-pos (loop [idx 1] ; start at 1 to skip current list item line
                                       (if (>= idx (count remaining))
                                         (count remaining) ; end of lines
                                         (if (list-item-line? (nth remaining idx))
                                           idx
                                           (recur (inc idx)))))
                  remaining-after (drop next-list-item-pos remaining)]
              (recur remaining-after (conj items list-item)))
            ;; Not a list item line - shouldn't happen, but skip it
            (recur (rest remaining) items)))))))

(defn same-list-type?
  "Check if two list item lines are of the same type"
  [line1 line2]
  (let [info1 (parse-list-item-line line1)
        info2 (parse-list-item-line line2)]
    (and info1 info2
         (= (:type info1) (:type info2)))))

(defn find-list-end
  "Find the index where the list ends"
  [lines]
  (when (list-item-line? (first lines))
    (let [first-line (first lines)]
      (loop [idx 0]
        (if (>= idx (count lines))
          (count lines) ; end of input
          (let [line (nth lines idx)]
            (cond
              ;; List item of same type - part of list, continue
              (and (list-item-line? line)
                   (same-list-type? first-line line))
              (recur (inc idx))

              ;; List item of different type - end of current list
              (list-item-line? line)
              idx

              ;; Blank line - might be part of list
              (str/blank? line)
              (if (and (< (inc idx) (count lines))
                       (let [next-line (nth lines (inc idx))]
                         (or (and (list-item-line? next-line)
                                  (same-list-type? first-line next-line))
                             (>= (get-line-indent next-line) 2)))) ; indented content
                (recur (inc idx))
                idx) ; end of list

              ;; Indented content - part of list
              (>= (get-line-indent line) 2)
              (recur (inc idx))

              ;; Non-list content - end of list
              :else
              idx)))))))

(defn parse-list
  "Parse a list (ordered or unordered) with proper multi-block support"
  [lines]
  (when (seq lines)
    (let [first-line (first lines)
          item-info (parse-list-item-line first-line)]
      (when item-info
        (let [list-type (:type item-info)
              list-items (parse-advanced-list-items lines)
              list-node (if (= :ordered list-type)
                          (make-node :ordered-list
                                     {:list-start (or (:start item-info) 1)
                                      :children list-items})
                          (make-node :bullet-list
                                     {:children list-items}))]
          list-node)))))

(defn html-block-line?
  "Check if a line starts an HTML block"
  [line]
  (and line
       (or (re-matches #"^\s*</?[a-zA-Z][^>]*>\s*$" line) ; Simple tag on its own line
           (re-matches #"^\s*<!--.*-->\s*$" line) ; HTML comment
           (re-matches #"^\s*<!\[CDATA\[.*\]\]>\s*$" line) ; CDATA section
           (re-matches #"^\s*<!DOCTYPE.*>\s*$" line))))

(defn thematic-break-line?
  "Check if a line is a thematic break (horizontal rule)"
  [line]
  (and line
       (let [trimmed (str/trim line)]
         ;; Match lines with 3+ asterisks, hyphens, or underscores, optionally with spaces
         (or (re-matches #"^(\*\s*){3,}$" trimmed) ; *** or * * *
             (re-matches #"^(-\s*){3,}$" trimmed) ; --- or - - -
             (re-matches #"^(_\s*){3,}$" trimmed))))) ; DOCTYPE

(defn parse-html-block
  "Parse HTML block content"
  [lines]
  (when (seq lines)
    (let [content (str/join "\n" lines)]
      (make-node :html-block {:literal content}))))

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

          ;; Thematic break (check before list items to avoid confusion)
          (thematic-break-line? line)
          (let [new-blocks (cond
                             (seq current-blockquote)
                             (conj blocks (parse-blockquote current-blockquote))

                             (seq current-para)
                             (conj blocks (parse-paragraph current-para))

                             :else blocks)]
            (recur rest-lines
                   (conj new-blocks (thematic-break-node))
                   []
                   []
                   false
                   []))

          ;; List item
          ;; List item - consume entire list at once
          (list-item-line? line)
          (let [new-blocks (cond
                             (seq current-blockquote)
                             (conj blocks (parse-blockquote current-blockquote))

                             (seq current-para)
                             (conj blocks (parse-paragraph current-para))

                             :else blocks)
                list-end-idx (find-list-end remaining)
                list-lines (take list-end-idx remaining)
                list-block (parse-list list-lines)
                lines-consumed list-end-idx
                remaining-after (drop lines-consumed remaining)]
            (recur remaining-after
                   (conj new-blocks list-block)
                   []
                   []
                   false
                   []))

          ;; Blockquote
          (blockquote-line? line)
          (let [new-blocks (cond
                             (seq current-para)
                             (conj blocks (parse-paragraph current-para))

                             :else blocks)]
            (recur rest-lines
                   new-blocks
                   []
                   (conj current-blockquote line)
                   false
                   []))

          ;; Blank line - end current block
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
          ;; Regular line - add to current paragraph (if not in other block)
          :else
          (cond
            (seq current-blockquote)
            ;; Continue blockquote with non-prefixed line
            (recur rest-lines
                   (conj blocks (parse-blockquote current-blockquote))
                   [line]
                   []
                   false
                   [])

            :else
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

    :bullet-list
    (str "<ul>" (str/join "" (map render-node (:children node))) "</ul>")

    :ordered-list
    (let [start (:list-start node)]
      (if (and start (not= start 1))
        (str "<ol start=\"" start "\">" (str/join "" (map render-node (:children node))) "</ol>")
        (str "<ol>" (str/join "" (map render-node (:children node))) "</ol>")))

    :list-item
    (str "<li>" (str/join "" (map render-node (:children node))) "</li>")

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

    :image
    (let [url (:destination node)
          alt (str/join "" (map render-node (:children node)))
          title (:title node)]
      (if title
        (str "<img src=\"" url "\" alt=\"" alt "\" title=\"" title "\" />")
        (str "<img src=\"" url "\" alt=\"" alt "\" />")))

    :autolink
    ;; Autolink nodes render as links with the URL as both href and text
    (let [url (:destination node)]
      (str "<a href=\"" url "\">" url "</a>"))

    :html-inline
    ;; HTML inline nodes contain raw HTML that should be preserved
    (:literal node)

    :code
    ;; Inline code nodes should be wrapped in <code> tags
    (str "<code>" (:literal node) "</code>")

    :softbreak
    ;; Softbreak nodes render as a newline in HTML
    "\n"

    :hardbreak
    ;; Hard line break renders as HTML <br /> tag
    "<br />"

    :thematic-break
    ;; Thematic break renders as an HTML horizontal rule
    "<hr />"

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
- Thematic breaks (horizontal rules)

> This is a blockquote with some **formatting** inside.
> It can span multiple lines.

***

### Code Support

Here's some Clojure code:

```clojure
(defn parse [markdown-text]
  (let [lines (str/split-lines markdown-text)
        blocks (parse-blocks lines)
        doc (document-node)]
    (reduce append-child doc blocks)))
```

---

## Thematic Breaks

The parser supports various thematic break styles:

- Three or more asterisks: ***
- Three or more hyphens: ---
- Three or more underscores with spaces: - - -

- - -

## Conclusion

This parser demonstrates the **feasibility** of porting CommonMark to Clojure!

<img src=\"foo.jpg\" />

<a href=\"dude\">

</a>

Dude <a href=\"dude\"><a/> go"

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
  (demo)
  (parse "[long\nline](link)")
  (parse "1. List 1

   This is some text yo!
2. List 2

   This is still
   ```clojure
   Here!
   ```
`")
  (parse "<!--more-->")
  (parse "* dude `dude--dude`
  whatever")
  (parse "Test: <https://www.page.com/Bob's-page>")
  (parse "***

- - -

---

-----"))
