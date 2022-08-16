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

### Social sharing

Social media sites such as Facebook, Twitter, LinkedIn, etc. display neat little
preview cards when you share a link. These cards are populated from certain
`<meta>` tags (as described in "[How to add a social media share card to any
website](https://dev.to/mishmanners/how-to-add-a-social-media-share-card-to-any-website-ha8)",
by Michelle Mannering) and typically contain a title, description / summary, and
preview image.

By default, quickblog adds tags for the page title for all pages and
descriptions for the following pages:
- Index: `{{blog-description}}`
- Archive: Archive - `{{blog-description}}`
- Tags: Tags - `{{blog-description}}`
- Tag pages: Posts tagged "`{{tag}}`" - `{{blog-description}}`

If you specify a `:blog-image URL` option, a preview image will be added to the
index, archive, tags, and tag pages. The URL should point to an image that is
1200x630 and maximum 5MB in size. It may either be an absolute URL or a URL
relative to `:blog-root`.

For post pages, meta tags will be populated from `Description` and `Image`
metadata in the document. For example, a post could look like this:

``` text
Title: Sharing is caring
Date: 2022-08-16
Tags: demo
Description: quickblog now creates nifty social media sharing cards / previews. Read all about how this works and how you can maximise engagement with your posts!
Image: assets/2022-08-16-sharing-preview.png

You may have already heard the good news: quickblog is more social than ever!
...
```

The value of the `Image` field is either an absolute URL or a URL relative to
`:blog-root`. As noted above, images should be 1200x630 and maximum 5MB in size
for best results.

Resources for understanding and testing social sharing:
- [Meta Tags debugger](https://metatags.io/)
- [Facebook Sharing Debugger](https://developers.facebook.com/tools/debug/)
- [LinkedIn Post Inspector](https://www.linkedin.com/post-inspector/)
- [Twitter Card Validator](https://cards-dev.twitter.com/validator)

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
