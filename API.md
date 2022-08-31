# quickblog.api 





## `clean`
``` clojure

(clean opts)
```


Removes cache and output directories
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L493-L499)</sub>
## `migrate`
``` clojure

(migrate opts)
```


Migrates from `posts.edn` to post-local metadata
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L501-L512)</sub>
## `new`
``` clojure

(new opts)
```


Creates new `file` in posts dir.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L466-L491)</sub>
## `quickblog`
``` clojure

(quickblog opts)
```


Alias for `render`
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L457-L460)</sub>
## `refresh-templates`
``` clojure

(refresh-templates opts)
```


Updates to latest default templates
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L514-L517)</sub>
## `render`
``` clojure

(render opts)
```


Renders posts declared in `posts.edn` to `out-dir`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L421-L455)</sub>
## `serve`
``` clojure

(serve opts)
```


Runs file-server on `port`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L519-L532)</sub>
## `watch`
``` clojure

(watch opts)
```


Watches posts, templates, and assets for changes. Runs file server using
  `serve`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L536-L601)</sub>
# quickblog.cli 





## `-main`
``` clojure

(-main & args)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L143-L144)</sub>
## `dispatch`
``` clojure

(dispatch)
(dispatch default-opts & args)
```

<sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L127-L133)</sub>
## `run`
``` clojure

(run default-opts)
```


Meant to be called using `clj -M:quickblog`; see Quickstart > Clojure in README
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/cli.clj#L135-L141)</sub>
