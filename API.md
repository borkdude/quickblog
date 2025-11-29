# quickblog.api 





## `clean`
``` clojure

(clean opts)
```


Removes cache and output directories
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L580-L586)</sub>
## `debug`
``` clojure

(debug & xs)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L277-L279)</sub>
## `migrate`
``` clojure

(migrate opts)
```


Migrates from `posts.edn` to post-local metadata
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L588-L599)</sub>
## `new`
``` clojure

(new opts)
```


Creates new `file` in posts dir.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L524-L578)</sub>
## `quickblog`
``` clojure

(quickblog opts)
```


Alias for `render`
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L515-L518)</sub>
## `refresh-templates`
``` clojure

(refresh-templates opts)
```


Updates to latest default templates
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L601-L604)</sub>
## `render`
``` clojure

(render opts)
```


Renders posts declared in `posts.edn` to `out-dir`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L475-L513)</sub>
## `serve`
``` clojure

(serve opts)
(serve opts block?)
```


Runs file-server on `port`. If `block?` is falsey, returns a zero-arity
  `stop-server!` function that will stop the server when called.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L606-L624)</sub>
## `unwatch`
``` clojure

(unwatch watchers)
```


Stops each watcher in the list of `watchers`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L712-L717)</sub>
## `update-cache-dir`
``` clojure

(update-cache-dir opts)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L190-L198)</sub>
## `watch`
``` clojure

(watch opts)
```


Watches posts, templates, and assets for changes. Runs file server using
  `serve` (unless the `:serve` opt is `false`). If the `:block` opt is `false`,
  returns a list of watchers that can be passed to `unwatch` to stop watching.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L628-L710)</sub>
# quickblog.cli 





## `-main`
``` clojure

(-main & args)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L144-L145)</sub>
## `dispatch`
``` clojure

(dispatch)
(dispatch default-opts & args)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L128-L134)</sub>
## `run`
``` clojure

(run default-opts)
```


Meant to be called using `clj -M:quickblog`; see Quickstart > Clojure in README
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L136-L142)</sub>
# quickblog.internal.frontmatter 





## `flatten-metadata`
``` clojure

(flatten-metadata metadata)
```


Given a list of maps which contain a single key/value, flatten them all into
  a single map with all the leading spaces removed. If an empty list is provided
  then return nil.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L20-L39)</sub>
## `parse-edn-metadata-headers`
``` clojure

(parse-edn-metadata-headers lines-seq)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L67-L77)</sub>
## `parse-metadata-headers`
``` clojure

(parse-metadata-headers lines-seq)
```


Given a sequence of lines from a markdown document, attempt to parse a
  metadata header if it exists. Accepts wiki, yaml, and edn formats.
  Returns the parsed headers number of lines the metadata spans
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L79-L95)</sub>
## `parse-metadata-line`
``` clojure

(parse-metadata-line line)
```


Given a line of metadata header text return either a list containing a parsed
  and normalizd key and the original text of the value, or if no header is found
  (this is a continuation or new value from a pervious header key) simply
  return the text. If a blank or invalid line is found return nil.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L6-L18)</sub>
## `parse-wiki-metadata-headers`
``` clojure

(parse-wiki-metadata-headers lines-seq)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L42-L49)</sub>
## `parse-yaml-metadata-headers`
``` clojure

(parse-yaml-metadata-headers lines-seq)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/internal/frontmatter.clj#L53-L65)</sub>
