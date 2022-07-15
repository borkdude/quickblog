# quickblog.api 





## `new`
``` clojure

(new {:keys [file title]})
```


Creates new entry in `posts.edn` and creates `file` in `posts` dir.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L211-L226)</sub>
## `quickblog`
``` clojure

(quickblog opts)
```


Alias for `render`
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L202-L205)</sub>
## `render`
``` clojure

(render {:keys [blog-title out-dir], :or {out-dir "public"}, :as opts})
```


Renders posts declared in `posts.edn` to `out-dir`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L169-L200)</sub>
## `serve`
``` clojure

(serve {:keys [port], :or {port 1888}})
```


Runs file-server on `port`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L228-L234)</sub>
## `watch`
``` clojure

(watch
 {:keys [watch-script],
  :or {watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"},
  :as opts})
```


Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L236-L262)</sub>
