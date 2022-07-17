(ns quickblog.internal
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [selmer.parser :as selmer]))

(defn stale? [src target]
  (seq (fs/modified-since target src)))

(defn copy-modified [src target]
  (when (stale? src target)
    (println "Writing" (str target))
    (fs/create-dirs (.getParent (fs/file target)))
    (fs/copy src target {:replace-existing true})))

(defn copy-tree-modified [src-dir target-dir out-dir]
  (let [modified-paths (fs/modified-since (fs/file target-dir)
                                          (fs/file src-dir))
        new-paths (->> (fs/glob src-dir "**")
                       (remove #(fs/exists? (fs/file out-dir %))))]
    (doseq [path (concat modified-paths new-paths)
            :let [target-path (fs/file out-dir path)]]
      (fs/create-dirs (.getParent target-path))
      (println "Writing" (str target-path))
      (fs/copy (fs/file path) target-path))))

(defn load-posts [{:keys [posts-file]}]
  (->> (edn/read-string (format "[%s]" (slurp posts-file)))
       (map (fn [{:keys [tags categories] :as post}]
              (assoc post :tags (or tags categories))))
       (sort-by :date (comp - compare))))

(defn posts-by-tag [posts]
  (->> posts
       (sort-by :date)
       (mapcat (fn [{:keys [tags] :as post}]
                 (map (fn [tag] [tag post]) tags)))
       (reduce (fn [acc [tag post]]
                 (update acc tag #(conj % post)))
               {})))

(defn post-links
  ([title posts]
   (post-links title posts {}))
  ([title posts {:keys [relative-path]}]
   [:div {:style "width: 600px;"}
    [:h1 title]
    [:ul.index
     (for [{:keys [file title date preview]} posts
           :when (not preview)]
       [:li [:span
             [:a {:href (str relative-path (str/replace file ".md" ".html"))}
              title]
             " - "
             date]])]]))

(defn tag-links [title tags]
  [:div {:style "width: 600px;"}
   [:h1 title]
   [:ul.index
    (for [[tag posts] tags]
      [:li [:span
            [:a {:href (str tag ".html")} tag]
            " - "
            (count posts)
            " posts"]])]])

(defn write-tag! [{:keys [blog-title]
                   :as opts}
                  tags-out-dir
                  template
                  [tag posts]]
  (let [tag-slug (str/replace tag #"[^A-z0-9]" "-")
        tag-file (fs/file tags-out-dir (str tag-slug ".html"))]
    (println "Writing tag page:" (str tag-file))
    (spit tag-file
          (selmer/render template
                         (merge opts
                                {:skip-archive true
                                 :title (str blog-title " - Tag - " tag)
                                 :relative-path "../"
                                 :body (hiccup/html (post-links (str "Tag - " tag) posts
                                                                {:relative-path "../"}))})))))
