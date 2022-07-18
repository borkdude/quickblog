# Quickblog

The blog code powering my [blog](https://blog.michielborkent.nl/).

See [API.md](API.md) on how to use this.

Compatible with [babashka](#babashka) and [Clojure](#clojure).

Includes hot-reload. See it in action [here](https://twitter.com/borkdude/status/1547912740156583936).

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

## Features

### favicon

To enable a [favicon](https://en.wikipedia.org/wiki/Favicon), set the following
key of the quickblog opts:
- `:favicon-template` - path to a file containing an HTML fragment to insert
  into the head of all pages. Unless you need something specific, you can set it
  to `templates/favicon.html` and quickblog will copy a [default favicon
  template](resources/templates/favicon.html) to that path next time you render.

If you want quickblog to manage your favicon assets, set these keys as well:
- `:favicon-local-dir` - local directory containing the favicon assets. If this
  is not set, the assumption is that your favicon assets are deployed by
  something other than quickblog (for example, your blog might be a subdirectory
  of your website, and the favicon assets might be managed by your website, in
  which case you just want the template to point to them, but don't need
  quickblog to manage the assets for you).
- `:favicon-remote-dir` - [optional; default: `""`] directory relative to
  `out-dir` in which to deploy the assets. It is recommended to keep your
  favicon assets at the root of your site if possible, so you probably don't
  need to change the default. This has no effect unless `:favicon-local-dir` is
  also set.

To fine-tune your favicon settings (probably because you're not using quickblog
to manage your favicon assets), set the following key:
- `:favicon-link-path` - [default: `""`] path relative to your blog root which will be
  prepended to the `link.href` attribute in the default template. This value is
  exposed as the `favicon-link-path` template variable.

You will also need to create the favicon assets themselves. The easiest way is
to use a favicon generator such as
[RealFaviconGenerator](https://realfavicongenerator.net/), which will let you
upload an image and then gives you a ZIP file containing all of the assets,
which you should unzip into a directory corresponding to the value of the
`:favicon-local-path` key as detailed above.

You can read an example of how to prepare a favicon here: https://jmglov.net/blog/2022-07-05-hacking-blog-favicon.html

**Example**

Assuming the favicon assets have been unzipped in the `favicon` direction, add
the following to `opts`:

``` clojure
:favicon-template "templates/favicon.html"
:favicon-local-path "favicon"
```

## Improvements

Feel free to send PRs for improvements.

My wishlist:

- Category links
- There might be a few things hardcoded that still need to be made configurable.
- Upstream improvements to [markdown-clj](https://github.com/yogthos/markdown-clj)
