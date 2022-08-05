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
delete    Delete blog article
quickblog Render blog
watch     Watch posts and templates and render file changes
publish   Publish blog
clean     Remove cache and output directories
migrate   Migrate away from `posts.edn` to metadata in post files
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

**NOTE:** when enabling or disabling a favicon, you must do a full re-render of
your site by running `bb clean` and then your `bb render` command.

To enable a [favicon](https://en.wikipedia.org/wiki/Favicon), add `:favicon
true` to your quickblog opts (or use `--favicon true` on the command line).
quickblog will render the contents of `templates/favicon.html` and insert them
in the head of your pages.

You will also need to create the favicon assets themselves. The easiest way is
to use a favicon generator such as
[RealFaviconGenerator](https://realfavicongenerator.net/), which will let you
upload an image and then gives you a ZIP file containing all of the assets,
which you should unzip into your `:assets-dir` (which defaults to `assets`).

You can read an example of how to prepare a favicon here:
https://jmglov.net/blog/2022-07-05-hacking-blog-favicon.html

quickblog's default template expects the favicon files to be named as follows:
- `android-chrome-192x192.png`
- `android-chrome-512x512.png`
- `apple-touch-icon.png`
- `browserconfig.xml`
- `favicon-16x16.png`
- `favicon-32x32.png`
- `favicon.ico`
- `mstile-150x150.png`
- `safari-pinned-tab.svg`
- `site.webmanifest`

If any of these files are not present in your `:assets-dir`, a quickblog default
will be copied there from `resources/quickblog/assets`.

## Breaking changes

### posts.edn removed

quickblog now keeps metadata for each blog post in the post file itself. It used
to use a `posts.edn` file for this purpose. If you are upgrading from a version
that used `posts.edn`, you should run `bb migrate` and then remove the
`posts.edn` file.

## Improvements

Feel free to send PRs for improvements.

My wishlist:

- There might be a few things hardcoded that still need to be made configurable.
- Upstream improvements to [markdown-clj](https://github.com/yogthos/markdown-clj)
