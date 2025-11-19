# Quickblog

The blog code powering my [blog](https://blog.michielborkent.nl/).

See [API.md](API.md) on how to use this.

Compatible with [babashka](#babashka) and [Clojure](#clojure).

Includes hot-reload. See it in action [here](https://twitter.com/borkdude/status/1547912740156583936).

## Blogs using quickblog

Instances of quickblog can be seen here:

- [Michiel Borkent's blog](https://blog.michielborkent.nl)
- [Josh Glover's blog](https://jmglov.net/blog)
- [Jeremy Taylor's blog](https://jdt.me/strange-reflections.html)
- [JP Monetta's blog](https://jpmonettas.github.io/my-blog/public/)
- [Luc Engelen's blog](https://blog.cofx.nl/) - ([source](https://github.com/cofx22/blog))
- [Rattlin.blog](https://rattlin.blog/)
- [REP‘ti’L‘e’](https://kuna.us/)
- [Søren Sjørup's blog](https://zoren.dk)
- [Henry Widd's blog](https://widdindustries.com/blog)
- [Anders means different](https://www.eknert.com/blog) - ([source](https://github.com/anderseknert/blog))
- [Kira McLean's programming blog](https://codewithkira.com) - ([source](https://github.com/kiramclean/kiramclean.github.io))
- [Ed Porras' blog](https://digressed.net)
- [Saket Patel](https://blog.saketpatel.me/)
- [Paul Butcher's blog](https://paulbutcher.com/)
- [Alex Sheluchin's blog](https://fnguy.com/) - ([source](https://github.com/sheluchin/blog))

### Articles about quickblog

- [Quickblog by Anders Means Different](https://www.eknert.com/blog/quickblog)

Feel free to PR yours.

## Quickstart

### Babashka

Since v0.4.7 quickblog requires babashka v1.12.201 since it relies on the
built-in [Nextjournal Markdown](https://github.com/nextjournal/markdown)
library.

Quickblog is intended to be used as a library from your babashka project. The
easiest way to use it is to add a task to your project's `bb.edn`.

This example assumes a basic `bb.edn` like this:

``` clojure
{:deps {io.github.borkdude/quickblog
        #_"You use the newest SHA here:"
        {:git/sha "3a1d6aff07f692f6e62606317f3d9e981b1df702"}}
 :tasks
 {:requires ([quickblog.cli :as cli])
  :init (def opts {:blog-title "REPL adventures"
                   :blog-description "A blog about blogging quickly"})
  quickblog {:doc "Start blogging quickly! Run `bb quickblog help` for details."
             :task (cli/dispatch opts)}}}
```

To create a new blog post:

``` clojure
$ bb quickblog new --file "test.md" --title "Test"
```

To start an HTTP server and re-render on changes to files:

```
$ bb quickblog watch
```

### Clojure

Quickblog can be used in Clojure with the exact same API as the bb tasks.
Default options can be configured in `:exec-args`.

``` clojure
:quickblog
{:deps {io.github.borkdude/quickblog
        #_"You use the newest SHA here:"
        {:git/sha "3a1d6aff07f692f6e62606317f3d9e981b1df702"}
        org.babashka/cli {:mvn/version "0.3.35"}}
 :main-opts ["-m" "babashka.cli.exec" "quickblog.cli" "run"]
 :exec-args {:blog-title "REPL adventures"
             :blog-description "A blog about blogging quickly"}}
```

After configuring this, you can call:

```
$ clj -M:quickblog new --file "test.md" --title "Test"
```

To watch:

```
$ clj -M:quickblog watch
```

etc.

## Features

### Markdown

Posts are written in Markdown and processed by
[markdown-clj](https://github.com/yogthos/markdown-clj), which implements the
[MultiMarkdown](https://github.com/fletcher/MultiMarkdown/wiki/MultiMarkdown-Syntax-Guide)
flavour of Markdown.

### Metadata

Post metadata is specified in the post file using [MultiMarkdown's metadata
tags](https://github.com/fletcher/MultiMarkdown/wiki/MultiMarkdown-Syntax-Guide#metadata).
quickblog expects three pieces of metadata in each post:
- `Title` - the title of the post
- `Date` - the date when the post will be published (used for sorting posts, so
  [ISO 8601](https://en.wikipedia.org/wiki/ISO_8601) datetimes are recommended)
- `Tags` - a comma-separated list of tags
- `Preview`: when `true`, the post won't be listed in the
  archive or tags page, but is still accessible via the direct link.

`quickblog new` requires the title to be specified and provides sensible
defaults for `Date` and `Tags`.

You can add any metadata fields to posts that you want. See the [Social
sharing](#social-sharing) section below for some useful suggestions.

**Note: metadata may not include newlines!**

### favicon

**NOTE:** when enabling or disabling a favicon, you must do a full re-render of
your site by running `bb quickblog clean` and then your `bb quickblog render`
command.

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

quickblog's [base
template](https://github.com/borkdude/quickblog/blob/389833f393e04d4176ef3eaa5047fa307a5ff2e8/resources/quickblog/templates/base.html)
adds meta tags for the page title for all pages and descriptions for the
following pages:
- Index: `{{blog-description}}`
- Archive: Archive - `{{blog-description}}`
- Tags: Tags - `{{blog-description}}`
- Tag pages: Posts tagged "`{{tag}}`" - `{{blog-description}}`

If you specify a `:blog-image URL` option, a preview image will be added to the
index, archive, tags, and tag pages. The URL should point to an image; for best
results, the image should be 1200x630 and maximum 5MB in size. It may either be
an absolute URL or a URL relative to `:blog-root`.

For post pages, meta tags will be populated from `Title`, `Description`,
`Image`, `Image-Alt`, and `Twitter-Handle` metadata in the document.

If not specified, `Twitter-Handle` defaults to the `:twitter-handle` option to
quickblog. The idea is that the `:twitter-handle` option is the Twitter handle
of the person owning the blog, who is likely also the author of most posts on
the blog. If there's a guest post, however, the guest blogger can add their
Twitter handle instead.

For example, a post could look like this:

``` text
Title: Sharing is caring
Date: 2022-08-16
Tags: demo
Image: assets/2022-08-16-sharing-preview.png
Image-Alt: A leather-bound notebook lies open on a writing desk
Twitter-Handle: quickblog
Description: quickblog now creates nifty social media sharing cards / previews. Read all about how this works and how you can maximise engagement with your posts!

You may have already heard the good news: quickblog is more social than ever!
...
```

The value of the `Image` field is either an absolute URL or a URL relative to
`:blog-root`. As noted above, images should be 1200x630 and maximum 5MB in size
for best results.

`Image-Alt` provides alt text for the preview image, which is extremely
important for making pages accessible to people using screen readers. I highly
recommend reading resources like "[Write good Alt Text to describe
images](https://accessibility.huit.harvard.edu/describe-content-images)" to
learn more.

Resources for understanding and testing social sharing:
- [Meta Tags debugger](https://metatags.io/)
- [Facebook Sharing Debugger](https://developers.facebook.com/tools/debug/)
- [LinkedIn Post Inspector](https://www.linkedin.com/post-inspector/)
- [Twitter Card Validator](https://cards-dev.twitter.com/validator)

### Linking to previous and next posts

If you set the `:link-prev-next-posts` option to `true`, quickblog adds `prev`
and `next` metadata to each post (where `prev` is the previous post and `next`
is the next post in date order, oldest to newest). You can make use of these by
adding something similar to this to your `post.html` template:

``` html
{% if any prev next %}
  <div class="post-prev-next">
{% if prev %}
    <div>⏪ <a href="{{prev.file|replace:.md:.html}}">{{prev.title}}</a></div>
{% endif %}
{% if next %}
    <div><a href="{{next.file|replace:.md:.html}}">{{next.title}}</a> ⏩</div>
{% endif %}
  </div>
{% endif %}
```

## Templates

quickblog uses the following templates in site generation:
- `base.html` - All pages. Page body is provided by the `{{body}}` variable.
- `post.html` - Post bodies.
- `style.css` - Styles for all pages.
- `favicon.html` - If `:favicon true`, used to include favicon in the `<head>`
  of all pages.
- `tags.html` - Tag overview page.
- `post-links.html` - Used to render lists of blog posts in the archive and
  each page corresponding to a single tag.
- `index.html` - Index page. Posts containing the marker comment
  `<!-- end-of-preview -->` are included on the index page up until the first
  occurrence of that comment.

quickblog looks for these templates in your `:templates-dir`, and if it doesn't
find them, will copy a default template into that directory. It is recommended
to keep `:templates-dir` under revision control so that you can modify the
templates to suit your needs and preferences.

The default templates are occasionally modified to support new features. When
this happens, you won't be able to use the new feature without making the same
modifications to your local templates. The easiest way to do this is to run `bb
quickblog refresh-templates`.

### New posts

In addition to the HTML templates above, you can also use a template for
generating new posts. Assuming you have a template `new-post.md` that looks like
this:

``` markdown
Title: {{title}}
Date: {{date}}
Tags: {{tags|join:\",\"}}
Image: {% if image %}{{image}}{% else %}{{assets-dir}}/{{file|replace:.md:}}-preview.png{% endif %}
Image-Alt: {{image-alt|default:FIXME}}
Discuss: {{discuss|default:FIXME}}
{% if preview %}Preview: true\n{% endif %}
Write a blog post here!
```

you can generate a new post like this:

``` text
$ bb quickblog new --file "test.md" --title "Test" --preview --template-file new-post.md
```

And the resulting `posts/test.md` will look like this:

``` markdown
Title: Test
Date: 2024-01-19
Tags: clojure
Image: assets/test-preview.png
Image-Alt: FIXME
Discuss: FIXME
Preview: true

Write a blog post here!
```

**It is not recommended to keep your new post template in your templates-dir, as
any changes to the new post template will cause all of your existing posts to be
re-rendered, which is probably not what you want!**

## Serving an alternate content root

If your website contains a blog not at the content root of the webserver (for
example, https://example.com/blog), you may want `bb quickblog watch` to watch
the blog directory whilst serving the blog directory's parent as the content
root. Assuming that your website has a `bb.edn`, you can add a task similar to
the following to accomplish this:

``` clojure
{:deps {io.github.borkdude/quickblog {:git/sha "LATEST-SHA-HERE"}}
 :tasks
 {:requires ([quickblog.api :as quickblog])
  :init (def opts
          {:out-dir "public"
           ;; ...
           :blog {:blog-title "Some cool blog"
                  ;; ...
                  :assets-dir "blog/assets"
                  :out-dir "public/blog"
                  :posts-dir "blog/posts"
                  :templates-dir "blog/templates"}})

  ;; ...

  watch {:doc "Watch blog for changes"
         :task (do
                 (quickblog/watch (assoc (:blog opts)
                                         :no-serve? true
                                         :no-block? true))
                 (quickblog/serve (assoc (:blog opts)
                                         :out-dir (:out-dir opts))))}
  }}
```

## Breaking changes

### posts.edn removed

quickblog now keeps metadata for each blog post in the post file itself. It used
to use a `posts.edn` file for this purpose. If you are upgrading from a version
that used `posts.edn`, you should run `bb quickblog migrate` and then remove the
`posts.edn` file.

## Improvements

Feel free to send PRs for improvements.

My wishlist:

- There might be a few things hardcoded that still need to be made configurable.
- Upstream improvements to [markdown-clj](https://github.com/yogthos/markdown-clj)
