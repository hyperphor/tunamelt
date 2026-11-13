(ns hyperphor.tunamelt.uri
  "Object naming: hyperphor://<realm>/<type>/<id>."
  (:require [clojure.string :as str]))

;;; The URI format lives only here; storage keeps realm/type/id as separate columns.

(def scheme "hyperphor")

(defn full-uri?
  "True if s looks like a full object URI (vs. a bare type)."
  [s]
  (and (string? s) (str/includes? s "://")))

(defn format-uri
  "Build a URI string from {:realm :type :id}."
  [{:keys [realm type id]}]
  (str scheme "://" realm "/" type "/" id))

(defn parse-uri
  "Parse a URI string into {:realm :type :id}; throws if malformed."
  [s]
  (if-let [[_ realm type id] (re-matches #"hyperphor://([^/]+)/([^/]+)/(.+)" (str s))]
    {:realm realm :type type :id id}
    (throw (ex-info "Malformed tunamelt URI" {:uri s}))))

(defn new-id [] (str (random-uuid)))
