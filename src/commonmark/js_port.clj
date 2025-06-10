(ns commonmark.js-port
  "A naive imperative commonmark-js to Clojure port"
  (:refer-clojure :exclude [assoc!]))

(set! *warn-on-reflection* true)

(defn jmap [& kvs]
  (let [m (new java.util.HashMap)]
    (doseq [[k v] (partition 2 kvs)]
      (.put m k v))
    m))

(defn assoc! [^java.util.Map m k v]
  (.put m k v)
  m)

(-> (jmap :foo :bar)
    (assoc! :a 1))

(defn parse [input]
  (let [this (jmap  )]
    ))
