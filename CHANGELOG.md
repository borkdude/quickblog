# Changelog

[Quickblog](https://github.com/borkdude/quickblog): light-weight static blog engine for Clojure and babashka

Instances of quickblog can be seen [here](https://github.com/borkdude/quickblog?tab=readme-ov-file#blogs-using-quickblog).

## 0.4.7 (2025-06-12)

- Switch to [Nextjournal Markdown](https://github.com/nextjournal/markdown) for markdown rendering
  The minimum babashka version to be used with quickblog is now v1.12.201 since it comes with Nextjournal Markdown built-in.
- Link to previous and next posts; see "Linking to previous and next posts" in
  the README ([@jmglov](https://github.com/jmglov))
- Fix flaky caching tests ([@jmglov](https://github.com/jmglov))
- Fix argument passing in test runner ([@jmglov](https://github.com/jmglov))
- Add `--date` to api/new. ([@jmglov](https://github.com/jmglov))
- Support Selmer template for new posts in api/new; see [Templates > New
  posts](README.md#new-posts) in README. ([@jmglov](https://github.com/jmglov))
- Add 'language-xxx' to pre/code blocks
- Fix README.md with working version in quickstart example
- Fix [#104](https://github.com/borkdude/quickblog/issues/104): fix caching with respect to previews
- Fix [#104](https://github.com/borkdude/quickblog/issues/104): document `:preview` option

## 0.3.6 (2023-12-31)

- Fix caching (this is hard)

## 0.3.5 (2023-12-31)

- Better caching when switching between watch and render

## 0.3.4 (2023-12-31)

- Fix caching in watch mode

## 0.3.3 (2023-12-27)

- [#86](https://github.com/borkdude/quickblog/issues/86): group archive page by year
- [#85](https://github.com/borkdude/quickblog/issues/85): don't render discuss links when `:discuss-link` isn't set
- [#84](https://github.com/borkdude/quickblog/issues/84): sort tags by post count
- [#80](https://github.com/borkdude/quickblog/issues/80): Generate an `about.html` when a template exists ([@elken](https://github.com/elken))
- [#78](https://github.com/borkdude/quickblog/issues/78): Allow configurable :page-suffix to omit `.html` from page links ([@anderseknert](https://github.com/anderseknert))
- [#76](https://github.com/borkdude/quickblog/pull/76): Remove livejs script tag
  on render. ([@jmglov](https://github.com/jmglov))
- [#75](https://github.com/borkdude/quickblog/pull/75): Omit preview posts from
  index. ([@jmglov](https://github.com/jmglov))
- Support capitalization of tags
- [#66](https://github.com/borkdude/quickblog/issues/66): Unambigous ordering of posts, sorting by date (descending), post title, and then file name.  ([@UnwarySage](https://github.com/UnwarySage))

## 0.2.3 (2023-01-30)

- Improve visualization on mobile screens ([@MatKurianski](https://github.com/MatKurianski))
- [#51](https://github.com/borkdude/quickblog/issues/51): Enable custom default tags or no tags ([@formsandlines](https://github.com/formsandlines))
- Enable use of metadata in templates ([@ljpengelen](https://github.com/ljpengelen))
- Replace workaround that copies metadata from `api/serve`
- [#61](https://github.com/borkdude/quickblog/issues/61): Add templates that allow control over layout and styling of index page, pages with tags, and archive ([@ljpengelen](https://github.com/ljpengelen))
- Preserve HTML comments ([@ljpengelen](https://github.com/ljpengelen))
- Support showing previews of posts on index page

## 0.1.0 (2022-12-11)

- Add command line interface
- Watch assets dir for changes in watch mode
- Added `refresh-templates` task to update to latest templates
- Social sharing (preview for Facebook, Twitter, LinkedIn, etc.)
- Move metadata to post files and improve caching
- Favicon support

Thanks to Josh Glover ([@jmglov](https://github.com/jmglov)) for heavily contributing in this release!

## 0.0.1 (2022-07-17)

- Initial version
