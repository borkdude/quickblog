# quickblog.api 





## `new`
``` clojure

(new {:keys [file title]})
```


Creates new entry in `posts.edn` and creates `file` in `posts` dir.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L197-L211)</sub>
## `quickblog`
``` clojure

(quickblog {:keys [blog-title out-dir], :or {out-dir "public"}, :as opts})
```


Renders posts declared in `posts.edn` to `out-dir`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L161-L190)</sub>
## `serve`
``` clojure

(serve {:keys [port], :or {port 1888}})
```


Runs file-server on `port`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L213-L219)</sub>
## `watch`
``` clojure

(watch
 {:keys [watch-script],
  :or {watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"},
  :as opts})
```


Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L221-L247)</sub>
