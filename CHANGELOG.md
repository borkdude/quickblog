# Changelog

[Quickblog](https://github.com/borkdude/quickblog): light-weight static blog engine for Clojure and babashka

Instances of quickblog can be seen here:

- [Michiel Borkent's blog](https://blog.michielborkent.nl)
- [Josh Glover's blog](https://jmglov.net/blog)
- [Jeremy Taylor's blog](https://jdt.me/strange-reflections.html)

## Unreleased

- Support capitalization of tags

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
