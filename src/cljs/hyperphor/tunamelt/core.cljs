(ns hyperphor.tunamelt.core
  "Public API (ClojureScript): as on the JVM, but every function returns a Promise.
  The default store is the IndexedDB database \"tunamelt\"."
  (:refer-clojure :exclude [get find])
  (:require [hyperphor.tunamelt.idb :as idb]
            [hyperphor.tunamelt.store :as store]
            [hyperphor.tunamelt.uri :as uri]))

;; :store holds a Promise of a Store.
(defonce ^:private config (atom {:store nil :realm "default"}))

(defn configure!
  "Set :store (a Store, or an IndexedDB database name) and/or :realm."
  [{s :store r :realm}]
  (swap! config #(cond-> %
                   (string? s)                   (assoc :store (idb/open s))
                   (satisfies? store/Store s)    (assoc :store (js/Promise.resolve s))
                   r                             (assoc :realm r)))
  nil)

(defn- with-store
  "Promise of (f store); errors thrown by f become rejections."
  [f]
  (let [p (or (:store @config)
              (:store (swap! config update :store #(or % (idb/open "tunamelt")))))]
    (.then p f)))

(defn realm [] (:realm @config))

(defn- ->key [uri-or-type]
  (if (uri/full-uri? uri-or-type)
    (uri/parse-uri uri-or-type)
    {:realm (realm) :type (name uri-or-type) :id (uri/new-id)}))

(defn put
  "Upsert obj at a URI, or at a new id under bare type. opts {:mode :mutable|:append-only};
  mode is fixed at creation. Promise of {:uri :obj :meta}."
  ([uri-or-type obj] (put uri-or-type obj {}))
  ([uri-or-type obj opts]
   (with-store #(store/put* % (->key uri-or-type) obj opts))))

(defn put-many
  "Put a seq of [uri-or-type obj] or [uri-or-type obj opts] in one transaction."
  [entries]
  (with-store #(store/put-many* % (mapv (fn [[u obj opts]] [(->key u) obj (or opts {})]) entries))))

(defn get-entry
  "Promise of {:uri :obj :meta} for uri, or nil."
  [uri]
  (with-store #(store/get* % (uri/parse-uri uri))))

(defn get
  "Promise of the object at uri, or nil."
  [uri]
  (.then (get-entry uri) #(:obj %)))

(defn find
  "Promise of [{:uri :obj :meta}] of type, newest first. opts as on the JVM:
  :realm, :since/:until (inst or epoch ms), :limit, :attrs {k v}."
  ([type] (find type {}))
  ([type opts]
   (with-store #(store/find* % (name type) opts))))

(defn delete
  "Promise of true if uri existed; deletes it and its history."
  [uri]
  (with-store #(store/delete* % (uri/parse-uri uri))))

(defn history
  "Promise of revisions of uri, newest first."
  [uri]
  (with-store #(store/history* % (uri/parse-uri uri))))

(defn types
  "Promise of a sorted seq of type names, or a sorted {type count} map with :counts?."
  ([] (types {}))
  ([{:keys [realm counts?]}]
   (with-store #(.then (store/types* % realm) (fn [m] (if counts? m (keys m)))))))

(defn- uri-value? [x]
  (and (string? x) (uri/full-uri? x)
       (try (uri/parse-uri x) (catch :default _ nil))))

(defn refs
  "Promise of the forward references of the object at uri: {attr [uri ...]}."
  [uri]
  (.then (get uri)
         (fn [obj]
           (into {} (keep (fn [[k v]]
                            (when-let [us (seq (filter uri-value? (if (sequential? v) v [v])))]
                              [k (vec us)])))
                 obj))))

(defn referrers
  "Promise of entries (plus :attr) referring to uri, newest first. opts: :attr, :type."
  ([uri] (referrers uri {}))
  ([uri opts] (with-store #(store/referrers* % uri opts))))

(defn search
  "Promise of entries matching query, best first, each plus :rank and :snippet.
  Every word must match as a prefix. :raw? is not supported on IndexedDB."
  ([query] (search query {}))
  ([query opts] (with-store #(store/search* % query opts))))
