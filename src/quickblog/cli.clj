(ns quickblog.cli
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [quickblog.api :as api]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private main-cmd-name "quickblog")

(def ^:private specs
  {
   ;; Blog metadata
   :blog-title
   {:desc "Title of the blog"
    :ref "<title>"
    :default "quickblog"
    :require true
    :group :blog-metadata}

   :blog-author
   {:desc "Author's name"
    :ref "<name>"
    :default "Quick Blogger"
    :require true
    :group :blog-metadata}

   :blog-description
   {:desc "Blog description for subtitle and RSS feeds"
    :ref "<text>"
    :default "A blog about blogging quickly"
    :require true
    :group :blog-metadata}

   :blog-root
   {:desc "Base URL of the blog"
    :ref "<url>"
    :default "https://github.com/borkdude/quickblog"
    :require true
    :group :blog-metadata}

   ;; Optional metadata
   :about-link
   {:desc "Link to about the author page"
    :ref "<url>"
    :group :optional-metadata}

   :discuss-link
   {:desc "Link to discussion forum for posts"
    :ref "<url>"
    :group :optional-metadata}

   :twitter-handle
   {:desc "Author's Twitter handle"
    :ref "<handle>"
    :group :optional-metadata}

   ;; Post config
   :default-metadata
   {:desc "Default metadata to add to posts"
    :default {}
    :group :post-config}

   :num-index-posts
   {:desc "Number of most recent posts to show on the index page"
    :ref "<num>"
    :default 3
    :group :post-config}

   :posts-file
   {:desc "File containing deprecated post metadata (used only for `migrate`)"
    :ref "<file>"
    :default "posts.edn"
    :group :post-config}

   ;; Input directories
   :assets-dir
   {:desc "Directory to copy assets (images, etc.) from"
    :default "assets"
    :require true
    :group :input-directories}

   :posts-dir
   {:desc "Directory to read posts from"
    :ref "<dir>"
    :default "posts"
    :require true
    :group :input-directories}

   :templates-dir
   {:desc "Directory to read templates from; see Templates section in README"
    :default "templates"
    :require true
    :group :input-directories}

   ;; Output directories
   :out-dir
   {:desc "Base directory for outputting static site"
    :ref "<dir>"
    :default "public"
    :require true
    :group :output-directories}

   :assets-out-dir
   {:desc "Directory to write assets to (relative to :out-dir)"
    :ref "<dir>"
    :default "assets"
    :require true
    :group :output-directories}

   :tags-dir
   {:desc "Directory to write tags to (relative to :out-dir)"
    :default "tags"
    :require true
    :group :output-directories}

   ;; Caching
   :force-render
   {:desc "If true, pages will be re-rendered regardless of cache status"
    :default false
    :group :caching}

   :cache-dir
   {:desc "Directory to use for caching"
    :ref "<dir>"
    :default ".work"
    :require true
    :group :caching}

   :rendering-system-files
   {:desc "Files involved in rendering pages (only set if you know what you're doing!)"
    :ref "<file1> <file2>..."
    :default ["bb.edn" "deps.edn"]
    :coerce []
    :require true
    :group :caching}

   ;; Social sharing
   :blog-image
   {:desc "Blog thumbnail image URL; see Features > Social sharing in README"
    :ref "<url>"
    :group :social-sharing}

   ;; Favicon
   :favicon
   {:desc "If true, favicon will be added to all pages"
    :default false
    :group :favicon}

   :favicon-dir
   {:desc "Directory to read favicon assets from"
    :ref "<dir>"
    :default (fs/file "assets" "favicon")
    :group :favicon}

   :favicon-out-dir
   {:desc "Directory to write favicon assets to (relative to :out-dir)"
    :ref "<dir>"
    :default (fs/file "assets" "favicon")
    :group :favicon}

   ;; Command-specific opts
   :file
   {:desc "Filename of post (relative to posts-dir)"
    :ref "<filename>"
    :require true
    :coerce (fn [s] (if (str/ends-with? s ".md")
                      s
                      (format "%s.md" s)))
    :cmds #{"new"}}

   :title
   {:desc "Title of post"
    :ref "<title>"
    :require true
    :cmds #{"new"}}

   :port
   {:desc "Port for HTTP server to listen on"
    :ref "<port>"
    :default 1888
    :cmds #{"serve" "watch"}}
   })

(defn- apply-defaults [default-opts spec]
  (->> spec
       (map (fn [[k v]]
              (if-let [default-val (default-opts k)]
                [k (assoc v :default default-val)]
                [k v])))
       (into {})))

(defn- global-opts [all-specs]
  (->> all-specs
       (filter (fn [[_opt spec]] (:group spec)))
       (into {})))

(defn- subcommand-opts [all-specs cmd-name]
  (->> all-specs
       (filter (fn [[_ {:keys [cmds]}]] (contains? cmds cmd-name)))
       (into {})))

(defn- ->subcommand-help [all-specs cmd]
  (let [cmd-name (first (:cmds cmd))
        cmd-opts (subcommand-opts all-specs cmd-name)
        opts-str (if (empty? cmd-opts)
                   ""
                   (str "\n" (cli/format-opts {:spec cmd-opts, :indent 4})))]
    (format "  %s: %s%s" cmd-name (:desc cmd) opts-str)))

(defn- ->group-name [group]
  (-> group
      name
      str/capitalize
      (str/replace "-" " ")))

(defn- format-opts [all-specs]
  (->> (global-opts all-specs)
       (group-by (fn [[_opt spec]] (:group spec)))
       (map (fn [[group opts]]
              (let [spec (into {} opts)]
                (format "%s\n%s"
                        (->group-name group)
                        (cli/format-opts {:spec spec})))))
       (str/join "\n\n")))

(defn- print-help [all-specs cmds]
  (println
   (format
    "Usage: bb %s <subcommand> <options>

Subcommands:

%s

Options:

%s
"
    main-cmd-name
    (->> cmds
         (map (partial ->subcommand-help all-specs))
         (str/join "\n"))
    (format-opts all-specs)))
  (System/exit 0))

(defn- print-command-help [cmd-name all-specs]
  (let [cmd-opts (subcommand-opts all-specs cmd-name)
        opts-str (if (empty? cmd-opts)
                   ""
                   (format "Options:\n%s\n\n"
                           (cli/format-opts {:spec cmd-opts})))]
    (println
     (format "Usage: bb %s %s <options>\n\n%sGlobal options:\n\n%s"
             main-cmd-name cmd-name opts-str (format-opts all-specs)))))

(defn- mk-cmd [all-specs [cmd-name desc f]]
  {:cmds [cmd-name]
   :desc desc
   :spec (merge (global-opts all-specs) (subcommand-opts all-specs cmd-name))
   :error-fn
   (fn [{:keys [type cause msg option] :as data}]
     (if (= :org.babashka/cli type)
       (do
         (case cause
           :require
           (println
            (format "Missing required argument:\n%s"
                    (cli/format-opts {:spec (select-keys all-specs [option])})))
           (println msg))
         (System/exit 1))
       (throw (ex-info msg data))))
   :fn (fn [{:keys [opts]}]
         (when (:help opts)
           (print-command-help cmd-name all-specs)
           (System/exit 0))
         (f opts))})

(defn- mk-table [default-opts]
  (let [all-specs (apply-defaults default-opts specs)
        cmds
        (mapv (partial mk-cmd all-specs)
              [["render"
                "Render the blog"
                api/render]
               ["new"
                "Create new file in posts-dir"
                api/new]
               ["serve"
                "Run HTTP server for rendered site"
                api/serve]
               ["watch"
                "Run HTTP server, watching posts, templates, and assets for changes"
                api/watch]
               ["clean"
                "Remove cache and output directories"
                api/clean]
               ["refresh-templates"
                "Update to latest default templates; see the Templates section in README"
                api/refresh-templates]
               ["migrate"
                "Migrate from `posts.edn` to post-local metadata"
                api/migrate]])]
    (conj cmds
          {:cmds [], :fn (fn [m] (print-help all-specs cmds))})))

(defn dispatch
  ([]
   (dispatch {}))
  ([default-opts & args]
   (cli/dispatch (mk-table default-opts)
                 (or args
                     (seq *command-line-args*)))))

(defn -main [& args]
  (apply dispatch {} args))
