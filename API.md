# quickblog.api 





## `new`
``` clojure

(new {:keys [file title posts-dir], :or {posts-dir (:posts-dir default-opts)}, :as opts})
```


Creates new entry in `posts.edn` and creates `file` in `posts` dir.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L226-L245)</sub>
## `quickblog`
``` clojure

(quickblog opts)
```


Alias for `render`
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L217-L220)</sub>
## `render`
``` clojure

(render
 {:keys [blog-title cache-dir out-dir posts-dir discuss-link],
  :or {cache-dir (:cache-dir default-opts), out-dir (:out-dir default-opts), posts-dir (:posts-dir default-opts)},
  :as opts})
```


Renders posts declared in `posts.edn` to `out-dir`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L174-L214)</sub>
## `serve`
``` clojure

(serve {:keys [port out-dir], :or {port 1888, out-dir (:out-dir default-opts)}})
```


Runs file-server on `port`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L247-L254)</sub>
## `watch`
``` clojure

(watch
 {:keys [posts-dir watch-script],
  :or
  {posts-dir (:posts-dir default-opts),
   watch-script "<script type=\"text/javascript\" src=\"https://livejs.com/live.js\"></script>"},
  :as opts})
```


Watches `posts.edn`, `posts` and `templates` for changes. Runs file
  server using `serve`.
<br><sub>[source](https://github.com/borkdude/quickblog/blob/main/src/quickblog/api.clj#L256-L285)</sub>
