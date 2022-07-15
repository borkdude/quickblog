# Quickblog

The blog code powering my [blog](https://blog.michielborkent.nl/).

See [API.md](API.md) on how to use this.

## Quickstart

### Babashka

Copy the config from `bb.edn` in this project to your local `bb.edn`. Then run `bb tasks`:

```
$ bb tasks
The following tasks are available:

new       Create new blog article
quickblog Render blog
watch     Watch posts and templates and render file changes
publish   Publish blog
clean     Remove .work and public directory
```

To create a new blog post:

``` clojure
$ bb new :file "test.md" :title "Test"
```

To watch:

```
$ bb watch
```

### Clojure

Quickblog can be used in Clojure with the exact same API as the bb tasks.
Default options can be configured in `:exec-args`.

``` clojure
:quickblog {:deps {io.github.borkdude/quickblog
                   {:git/sha "b69c11f4292702f78a8ac0a9f32379603bebf2af"}
                   org.babashka/cli {:mvn/version "0.3.31"}}
            :main-opts ["-m" "babashka.cli.exec" "quickblog.api"]
            :exec-args {:blog-title "REPL adventures"
                        :out-dir "public"
                        :blog-root "https://blog.michielborkent.nl/"}}
```

After configuring this, you can call:

```
clj -M:quickblog new :file "test.md" :title "Test"
```

To watch:

```
clj -M:quickblog watch
```

etc.

## Improvements

Feel free to send PRs for improvements.

My wishlist:

- Category links
- There might be a few things hardcoded that still need to be made configurable.
