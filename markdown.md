# How commonmark.js parses markdown

- in `lib/blocks.js` it first parses the blocks in parse function (line 938)
- it repeatedly calls incorporateLine
- Then it parses all the inlines of all the blocks

# How commonmark-java parses markdown

- Main parsing function, returns document AST
- https://github.com/commonmark/commonmark-java/blob/main/commonmark/src/main/java/org/commonmark/internal/DocumentParser.java#L125
