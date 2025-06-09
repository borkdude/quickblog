# Missing CommonMark Features

Based on comprehensive testing of the CommonMark implementation, here's a summary of missing features from the [CommonMark specification](https://spec.commonmark.org/).

## ✅ Recently Implemented Features

### 1. Images ✅ IMPLEMENTED
- **Syntax**: `![alt text](url)` and `![alt text](url "title")`
- **Status**: ✅ **WORKING** - Properly renders as `<img>` tags
- **Example**:
  ```markdown
  ![Alt text](https://example.com/image.jpg)
  ```
  Now renders: `<img src="https://example.com/image.jpg" alt="Alt text" />`

### 2. Hard Line Breaks ✅ IMPLEMENTED  
- **Syntax**: Two spaces at end of line + newline
- **Status**: ✅ **WORKING** - Properly renders as `<br />` tags
- **Example**:
  ```markdown
  Line one  
  Line two
  ```
  Now renders: `<p>Line one<br />Line two</p>`

## Missing Core Features

### 3. Setext Headings ⭐ HIGH PRIORITY
- **Syntax**: `Heading\n=======` (h1) or `Heading\n-------` (h2)
- **Current behavior**: Renders as paragraph + horizontal rule
- **Should render**: `<h1>` or `<h2>` tags
- **Example**:
  ```markdown
  Heading 1
  =========
  
  Heading 2
  ---------
  ```
  Currently renders: `<p>Heading 1 =========</p><p>Heading 2</p><hr />`
  Should render: `<h1>Heading 1</h1><h2>Heading 2</h2>`

### 4. Reference Links ⭐ HIGH PRIORITY
- **Syntax**: `[text][ref]` with `[ref]: url` definitions
- **Current behavior**: Renders as literal text
- **Should render**: Proper `<a>` tags
- **Example**:
  ```markdown
  [link text][1]
  
  [1]: https://example.com
  ```
  Currently renders: `<p>[link text][1]</p><p>[1]: https://example.com</p>`
  Should render: `<p><a href="https://example.com">link text</a></p>`

### 5. Backslash Escaping ⭐ HIGH PRIORITY
- **Syntax**: `\*` escapes special characters
- **Current behavior**: Backslash renders literally, still processes markup
- **Should render**: Literal characters without markup
- **Example**:
  ```markdown
  \*not italic\* and \# not heading
  ```
  Currently renders: `<p>\<em>not italic\</em> and \# not heading</p>`
  Should render: `<p>*not italic* and # not heading</p>`

## Missing Inline Features

### 6. Underscore Emphasis
- **Syntax**: `_italic_` and `__bold__`
- **Current behavior**: Renders literally
- **Should render**: `<em>` and `<strong>` tags
- **Example**:
  ```markdown
  _italic_ and __bold__
  ```
  Currently renders: `<p>_italic_ and __bold__</p>`
  Should render: `<p><em>italic</em> and <strong>bold</strong></p>`

### 7. Nested Emphasis
- **Syntax**: `***bold italic***`
- **Current behavior**: Partial parsing, incorrect nesting
- **Should render**: `<strong><em>bold italic</em></strong>`
- **Example**:
  ```markdown
  ***bold and italic***
  ```
  Currently renders: `<p>*<strong>bold and italic</strong>*</p>`
  Should render: `<p><strong><em>bold and italic</em></strong></p>`

### 8. Multi-backtick Code Spans
- **Syntax**: `` ``code with `backtick` inside`` ``
- **Current behavior**: Incorrect parsing
- **Should render**: Single `<code>` element
- **Example**:
  ```markdown
  ``code with `backtick` inside``
  ```
  Currently renders: `<p>`<code>code with </code>backtick<code> inside</code>`</p>`
  Should render: `<p><code>code with `backtick` inside</code></p>`

### 9. Email Autolinks
- **Syntax**: `<user@example.com>`
- **Current behavior**: Renders literally
- **Should render**: `<a href="mailto:user@example.com">user@example.com</a>`
- **Example**:
  ```markdown
  <user@example.com>
  ```
  Currently renders: `<p><user@example.com></p>`
  Should render: `<p><a href="mailto:user@example.com">user@example.com</a></p>`

### 10. Link Titles
- **Syntax**: `[text](url "title")`
- **Current behavior**: Title included in URL
- **Should render**: `<a href="url" title="title">text</a>`
- **Example**:
  ```markdown
  [link](https://example.com "Title")
  ```
  Currently renders: `<p><a href="https://example.com "Title"">link</a></p>`
  Should render: `<p><a href="https://example.com" title="Title">link</a></p>`

## Missing Block Features

### 11. Indented Code Blocks
- **Syntax**: 4-space indented lines
- **Current behavior**: Renders as paragraph
- **Should render**: `<pre><code>` blocks
- **Example**:
  ```markdown
      function hello() {
          return 'world';
      }
  ```
  Currently renders: `<p>    function hello() {         return 'world';     }</p>`
  Should render: `<pre><code>function hello() {\n    return 'world';\n}</code></pre>`

### 12. HTML Entity Processing
- **Syntax**: `&amp;` `&lt;` `&#39;` etc.
- **Current behavior**: Renders literally
- **Should render**: Actual characters (`&`, `<`, `'`)
- **Example**:
  ```markdown
  &amp; &lt; &gt; &quot; &#39;
  ```
  Currently renders: `<p>&amp; &lt; &gt; &quot; &#39;</p>`
  Should render: `<p>&amp; &lt; &gt; " '</p>`

### 13. Proper HTML Block Processing
- **Syntax**: Raw HTML blocks with specific rules
- **Current behavior**: Basic recognition but wrapped in `<p>`
- **Should render**: Raw HTML pass-through
- **Example**:
  ```markdown
  <div>
    <p>Raw HTML</p>
  </div>
  ```
  Currently renders: `<p><div><p>Raw HTML</p></div></p>`
  Should render: `<div>\n  <p>Raw HTML</p>\n</div>`

### 14. Tight vs Loose Lists
- **Feature**: Lists with/without `<p>` tags based on spacing
- **Current behavior**: Always tight (no `<p>` wrapping)
- **Should render**: `<p>` tags when blank lines present
- **Example**:
  ```markdown
  - Item 1

  - Item 2
  ```
  Currently renders: `<ul><li>Item 1</li><li>Item 2</li></ul>`
  Should render: `<ul><li><p>Item 1</p></li><li><p>Item 2</p></li></ul>`

## Missing Advanced Features

### 15. Link Reference Definitions
- **Syntax**: `[label]: url "title"` at document level
- **Current behavior**: Renders as paragraph
- **Should render**: Invisible, used for reference resolution
- **Example**:
  ```markdown
  [foo]: /url "title"
  
  [foo]
  ```
  Currently renders: `<p>[foo]: /url "title"</p><p>[foo]</p>`
  Should render: `<p><a href="/url" title="title">foo</a></p>`

### 16. Shortcut Reference Links
- **Syntax**: `[Google]` with `[Google]: url`
- **Current behavior**: Renders literally
- **Should render**: Resolved link
- **Example**:
  ```markdown
  [Google]
  
  [Google]: https://google.com
  ```
  Currently renders: `<p>[Google]</p><p>[Google]: https://google.com</p>`
  Should render: `<p><a href="https://google.com">Google</a></p>`

### 17. Empty Link/Image Text
- **Syntax**: `[](url)` and `![](image.jpg)`
- **Current behavior**: Renders literally
- **Should render**: Links/images with empty text/alt
- **Example**:
  ```markdown
  [](url) and ![](image.jpg)
  ```
  Currently renders: `<p>[](url) and ![](image.jpg)</p>`
  Should render: `<p><a href="url"></a> and <img src="image.jpg" alt="" /></p>`

## Priority Ranking

### Must-have for basic CommonMark compliance:
1. ✅ **Images** - IMPLEMENTED
2. ✅ **Hard line breaks** - IMPLEMENTED  
3. **Setext headings** - Alternative heading syntax
4. **Backslash escaping** - Critical for literal text
5. **Reference links** - Important for documentation

### Important for real-world usage:
6. **Underscore emphasis** - Alternative emphasis syntax
7. **Indented code blocks** - Traditional code block syntax
8. **Link titles** - Enhanced link metadata
9. **HTML entities** - Proper character encoding

### Nice-to-have for full spec compliance:
10. **Email autolinks** - Convenient email linking
11. **Nested emphasis edge cases** - Advanced formatting
12. **Multi-backtick code spans** - Complex code examples
13. **Tight/loose list distinction** - Subtle formatting differences

## Implementation Notes

The library has good coverage of basic features including:
- ✅ ATX headings (`# Heading`)
- ✅ Inline links (`[text](url)`)
- ✅ Asterisk emphasis (`*italic*` and `**bold**`)
- ✅ Fenced code blocks (`` ```code``` ``)
- ✅ Lists (ordered and unordered)
- ✅ Blockquotes (`> text`)
- ✅ Basic autolinks (`<https://url>`)
- ✅ Thematic breaks (`---`)
- ✅ Inline code (`` `code` ``)

The missing features listed above would bring the implementation closer to full CommonMark specification compliance.
