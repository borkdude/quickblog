(ns quickblog.cli
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [quickblog.api :as api]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private main-cmd-name "quickblog")

(def ^:private specs (get-in (meta (the-ns 'quickblog.api))
                             [:org.babashka/cli :spec]))

(defn- apply-defaults [default-opts spec]
  (->> spec
       (map (fn [[k v]]
              (if-let [default-val (default-opts k)]
                [k (assoc v :default default-val)]
                [k v])))
       (into {})))

(defn- ->subcommand-help [{:keys [cmds cmd-opts desc]}]
  (let [cmd-name (first cmds)
        opts-str (if (empty? cmd-opts)
                   ""
                   (str "\n" (cli/format-opts {:spec cmd-opts, :indent 4})))]
    (format "  %s: %s%s" cmd-name desc opts-str)))

(defn- ->group-name [group]
  (-> group
      name
      str/capitalize
      (str/replace "-" " ")))

(defn- format-opts [global-specs]
  (->> global-specs
       (group-by (fn [[_opt spec]] (:group spec)))
       (map (fn [[group opts]]
              (let [spec (into {} opts)]
                (format "%s\n%s"
                        (->group-name group)
                        (cli/format-opts {:spec spec})))))
       (str/join "\n\n")))

(defn- print-help [global-specs cmds]
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
         (map ->subcommand-help)
         (str/join "\n"))
    (format-opts global-specs)))
  (System/exit 0))

(defn- print-command-help [cmd-name specs cmd-opts]
  (let [opts-str (if (empty? cmd-opts)
                   ""
                   (format "Options:\n%s\n\n"
                           (cli/format-opts {:spec cmd-opts})))]
    (println
     (format "Usage: bb %s %s <options>\n\n%sGlobal options:\n\n%s"
             main-cmd-name cmd-name opts-str (format-opts specs)))))

(defn- mk-cmd [global-specs [cmd-name desc fn-var]]
  (let [cmd-opts (get-in (meta fn-var) [:org.babashka/cli :spec])]
    {:cmds [cmd-name]
     :cmd-opts cmd-opts
     :desc desc
     :spec (merge global-specs cmd-opts)
     :error-fn
     (fn [{:keys [type cause msg option] :as data}]
       (if (= :org.babashka/cli type)
         (do
           (case cause
             :require
             (println
              (format "Missing required argument --%s:\n%s"
                      (name option)
                      (cli/format-opts {:spec cmd-opts})))
             (println msg))
           (System/exit 1))
         (throw (ex-info msg data))))
     :fn (fn [{:keys [opts]}]
           (when (:help opts)
             (print-command-help cmd-name global-specs cmd-opts)
             (System/exit 0))
           ;; If we have a var, we need to deref it to get the function out
           (if (var? fn-var)
             (@fn-var opts)
             (fn-var opts)))}))

(defn- mk-table [default-opts]
  (let [global-specs (apply-defaults default-opts specs)
        cmds
        (mapv (partial mk-cmd global-specs)
              [["render"
                "Render the blog"
                #'api/render]
               ["new"
                "Create new file in posts-dir"
                #'api/new]
               ["serve"
                "Run HTTP server for rendered site"
                ;; api/serve returns immediately, so we'll wait on a promise
                ;; after calling it to prevent the process from exiting. In
                ;; order for the help text to work, we need to grab the metadata
                ;; from the api/serve var and attach it to our fn. This is
                ;; admittedly gross and I should be scolded severely for this
                ;; nonsense. Send a pull request along the scolding, if you
                ;; don't mind!
                (with-meta
                  (fn [opts]
                    (api/serve opts)
                    @(promise))
                  (meta #'api/serve))]
               ["watch"
                "Run HTTP server, watching posts, templates, and assets for changes"
                #'api/watch]
               ["clean"
                "Remove cache and output directories"
                #'api/clean]
               ["refresh-templates"
                "Update to latest default templates; see the Templates section in README"
                #'api/refresh-templates]
               ["migrate"
                "Migrate from `posts.edn` to post-local metadata"
                #'api/migrate]])]
    (conj cmds
          {:cmds [], :fn (fn [m] (print-help global-specs cmds))})))

(defn dispatch
  ([]
   (dispatch {}))
  ([default-opts & args]
   (cli/dispatch (mk-table default-opts)
                 (or args
                     (seq *command-line-args*)))))

(defn run
  "Meant to be called using `clj -M:quickblog`; see Quickstart > Clojure in README"
  [default-opts]
  ;; *command-line-args* will start with `(quickblog.cli run ...)`, so we need to
  ;; get rid of the first two items to get at what we care about
  (let [args (drop 2 *command-line-args*)]
    (apply dispatch default-opts args)))

(defn -main [& args]
  (apply dispatch {} args))
