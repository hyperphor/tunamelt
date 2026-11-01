(ns hyperphor.tunamelt.core
  "Public API: put/get/find/delete/history of JSON-able objects under hyperphor://realm/type/id URIs."
  (:refer-clojure :exclude [get find])
  (:require [environ.core :refer [env]]
            [hyperphor.tunamelt.store :as store]
            [hyperphor.tunamelt.sqlite :as sqlite]
            [hyperphor.tunamelt.uri :as uri]))

;;; Config: env TUNAMELT_DB (default "tunamelt.db") and TUNAMELT_REALM (default "default"),
;;; or call configure!.

(defonce ^:private config (atom {:store nil
                                 :realm (or (env :tunamelt-realm) "default")}))

(defn configure!
  "Set :store (a Store, or a SQLite path string) and/or :realm."
  [{s :store r :realm}]
  (swap! config #(cond-> %
                   (string? s) (assoc :store (sqlite/open s))
                   (satisfies? store/Store s) (assoc :store s)
                   r (assoc :realm r))))

(defn- the-store []
  (or (:store @config)
      (:store (swap! config #(update % :store (fn [s] (or s (sqlite/open (or (env :tunamelt-db) "tunamelt.db")))))))))

(defn realm [] (:realm @config))

(defn- ->key
  "URI string or bare type → {:realm :type :id}, generating an id for a bare type."
  [uri-or-type]
  (if (uri/full-uri? uri-or-type)
    (uri/parse-uri uri-or-type)
    {:realm (realm) :type (name uri-or-type) :id (uri/new-id)}))

(defn put
  "Upsert obj at a URI, or at a new id under bare type. opts {:mode :mutable|:append-only};
  mode is fixed at creation. Returns {:uri :obj :meta}. Keyword values read back as strings."
  ([uri-or-type obj] (put uri-or-type obj {}))
  ([uri-or-type obj opts]
   (store/put* (the-store) (->key uri-or-type) obj opts)))

(defn put-many
  "Put a seq of [uri-or-type obj] or [uri-or-type obj opts] in one transaction."
  [entries]
  (store/put-many* (the-store)
                   (map (fn [[u obj opts]] [(->key u) obj (or opts {})]) entries)))

(defn get-entry
  "Return {:uri :obj :meta} for uri, or nil."
  [uri]
  (store/get* (the-store) (uri/parse-uri uri)))

(defn get
  "Return the object at uri, or nil."
  [uri]
  (:obj (get-entry uri)))

(defn find
  "Seq of {:uri :obj :meta} of type, newest first.
  opts: :realm, :since/:until (inst or epoch ms), :attrs {k scalar}, :limit."
  ([type] (find type {}))
  ([type opts]
   (store/find* (the-store) (name type) opts)))

(defn delete
  "Delete uri and its history. Returns true if it existed."
  [uri]
  (store/delete* (the-store) (uri/parse-uri uri)))

(defn history
  "Revisions of uri, newest first (a single entry for :mutable objects)."
  [uri]
  (store/history* (the-store) (uri/parse-uri uri)))

(defn types
  "Sorted seq of stored type names, or a sorted {type count} map if :counts? is true.
  opts: :realm limits to one realm."
  ([] (types {}))
  ([{:keys [realm counts?]}]
   (let [m (store/types* (the-store) realm)]
     (if counts? m (keys m)))))
