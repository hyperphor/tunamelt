(ns hyperphor.tunamelt.idb
  "IndexedDB backend (browsers, extension service workers). Objects are JSON text, as in the
  SQLite backend. Top-level attribute values are indexed in an attrs store (narrows find :attrs,
  drives referrers); text values are tokenized into a multiEntry index for prefix search."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperphor.tunamelt.codec :refer [kstr ->json <-json roundtrip]]
            [hyperphor.tunamelt.store :as store]
            [hyperphor.tunamelt.uri :as uri]))

;;; Records are plain JS objects with string keys, read with gobj/get so advanced
;;; compilation can't rename them.

(def ^:private db-version 1)

(defn- req->p
  "Promise of an IDBRequest's result."
  [^js req]
  (js/Promise. (fn [res rej]
                 (set! (.-onsuccess req) #(res (.-result req)))
                 (set! (.-onerror req) #(rej (.-error req))))))

(defn- run-tx
  "Promise of (f tx), resolved once the transaction commits. If f fails the transaction aborts.
  f must only await IDB requests, or the transaction auto-commits early."
  [^js db stores mode f]
  (let [tx (.transaction db (clj->js stores) mode)
        done (js/Promise. (fn [res rej]
                            (set! (.-oncomplete tx) res)
                            (set! (.-onerror tx) #(rej (.-error tx)))
                            (set! (.-onabort tx) #(rej (or (.-error tx) (js/Error. "Transaction aborted"))))))
        result (-> (js/Promise.resolve) (.then #(f tx)))]
    (.catch result (fn [_] (try (.abort tx) (catch :default _ nil))))
    (.then (js/Promise.all #js [result done]) #(aget % 0))))

(defn- now [] (js/Date.now))

(defn- ->millis [t]
  (cond (nil? t)    nil
        (number? t) t
        (inst? t)   (.getTime t)
        :else (throw (ex-info "Unsupported time value" {:value t}))))

(defn- k->arr [{:keys [realm type id]}] #js [realm type id])

(defn- rec-key [^js r] {:realm (gobj/get r "realm") :type (gobj/get r "type") :id (gobj/get r "id")})

(defn- rec->entry
  "Entry for an objects or revisions record; `mode` is needed for revisions."
  [^js r & [mode]]
  (let [k (rec-key r)
        get #(gobj/get r %)]
    {:uri  (uri/format-uri k)
     :obj  (<-json (get "obj"))
     :meta (cond-> (assoc k :tunamelt/mode (keyword (or mode (get "mode"))))
             (get "created") (assoc :created (get "created"))
             (get "updated") (assoc :updated (get "updated"))
             (get "ts")      (assoc :updated (get "ts"))
             (get "rev")     (assoc :rev (get "rev")))}))

(defn- rev-range [{:keys [realm type id]}]
  (js/IDBKeyRange.bound #js [realm type id (- js/Infinity)] #js [realm type id js/Infinity]))

;;; Attribute index: one record per top-level scalar (or array element), as in SQLite.

(defn- indexable? [v] (or (string? v) (boolean? v) (and (number? v) (not (js/isNaN v)))))

(defn- attr-values
  "{attr-string [values]} for indexable top-level scalars and arrays of scalars."
  [json-obj]
  (when (map? json-obj)
    (into {} (keep (fn [[k v]]
                     (when-let [xs (seq (distinct (filter indexable? (if (sequential? v) v [v]))))]
                       [(kstr k) (vec xs)])))
          json-obj)))

;; Booleans aren't valid IDB keys.
(defn- enc [v] (if (boolean? v) #js ["b" (if v 1 0)] v))

;;; Text index

(defn- word-re [] (js/RegExp. "[\\p{L}\\p{N}]+" "gu"))

(defn- fold [s] (-> s (.normalize "NFD") (.replace (js/RegExp. "\\p{M}+" "gu") "") (.toLowerCase)))

(defn- tokens [s] (vec (some-> (.match (fold s) (word-re)) array-seq)))

(defn- texts [attr-vals]
  (remove #(str/starts-with? % (str uri/scheme "://")) (filter string? (mapcat val attr-vals))))

(defn- unindex! [^js tx k]
  (let [attrs (.objectStore tx "attrs")]
    (-> (req->p (.getAllKeys (.index attrs "key") (k->arr k)))
        (.then (fn [pks] (js/Promise.all (.map pks #(req->p (.delete attrs %))))))
        (.then #(req->p (.delete (.objectStore tx "fts") (k->arr k)))))))

(defn- ^:async index! [^js tx {:keys [realm type id] :as k} json-obj]
  (await (unindex! tx k))
  (let [attrs (.objectStore tx "attrs")
        avs (attr-values json-obj)]
    (await (js/Promise.all
            (clj->js (for [[a xs] avs, x xs]
                       (req->p (.add attrs #js {"realm" realm "type" type "id" id "attr" a "v" (enc x)}))))))
    (when-let [ts (seq (texts avs))]
      (let [text (str/join "\n" ts)]
        (await (req->p (.put (.objectStore tx "fts")
                             #js {"realm" realm "type" type "id" id "text" text
                                  "tokens" (clj->js (distinct (tokens text)))})))))))

;;; find

(defn- obj-values [obj attr]
  (let [v (get obj (keyword (kstr attr)))]
    (set (filter indexable? (if (sequential? v) v [v])))))

(defn- attrs-pred
  "Predicate on a stored object for find :attrs. nil = attr absent; scalar = any value equals;
  vector = contains all. Throws on other values."
  [attrs]
  (let [bad #(throw (ex-info "find :attrs values must be scalars or vectors of scalars" {:attr %1 :value %2}))
        q (fn [k x] (let [r (roundtrip x)] (if (indexable? r) r (bad k x))))
        checks (mapv (fn [[k v]]
                       (cond (nil? v)        #(empty? (obj-values % k))
                             (map? v)        (bad k v)
                             (sequential? v) (let [qs (mapv #(q k %) v)] #(every? (obj-values % k) qs))
                             :else           (let [qv (q k v)] #(contains? (obj-values % k) qv))))
                     attrs)]
    (fn [obj] (every? #(% obj) checks))))

(defn- ^:async candidates
  "Object records for a find: via the attrs index for the first positive attr, else by type."
  [^js tx type {:keys [realm since until attrs]}]
  (let [objects (.objectStore tx "objects")
        [k v] (first (filter (comp some? val) attrs))
        v (if (sequential? v) (first v) v)]
    (if (some? v)
      (let [rows (await (req->p (.getAll (.index (.objectStore tx "attrs") "type_attr_value")
                                         #js [type (kstr k) (enc (roundtrip v))])))
            keys (distinct (map rec-key rows))]
        (loop [[k & more] keys acc []]
          (if k
            (recur more (conj acc (await (req->p (.get objects (k->arr k))))))
            (remove nil? acc))))
      (let [lo (or (->millis since) (- js/Infinity))
            hi (or (->millis until) js/Infinity)
            [idx lo hi] (if realm
                          ["type_realm_updated" #js [type realm lo] #js [type realm hi]]
                          ["type_updated" #js [type lo] #js [type hi]])]
        (array-seq (await (req->p (.getAll (.index objects idx)
                                           (js/IDBKeyRange.bound lo hi false (some? until))))))))))

(defn- ^:async find-entries [^js tx type {:keys [realm since until attrs limit] :as opts}]
  (let [pred (attrs-pred attrs)
        since (->millis since)
        until (->millis until)
        recs (await (candidates tx type opts))]
    (->> recs
         (filter (fn [r] (let [u (gobj/get r "updated")]
                           (and (= type (gobj/get r "type"))
                                (or (nil? realm) (= realm (gobj/get r "realm")))
                                (or (nil? since) (>= u since))
                                (or (nil? until) (< u until))))))
         (map rec->entry)
         (filter #(pred (:obj %)))
         (sort-by (comp :updated :meta) >)
         (#(if limit (take limit %) %))
         vec)))

;;; put / delete

(defn- ^:async put-one [^js tx {:keys [realm type id] :as k} obj {:keys [mode]}]
  (let [objects (.objectStore tx "objects")
        existing (await (req->p (.get objects (k->arr k))))
        old-mode (some-> existing (gobj/get "mode") keyword)
        mode (or mode old-mode :mutable)]
    (when-not (#{:mutable :append-only} mode)
      (throw (ex-info "Unknown tunamelt mode" {:mode mode})))
    (when (and old-mode (not= mode old-mode))
      (throw (ex-info "Cannot change mode of existing object" {:key k :mode old-mode :requested mode})))
    (let [t (now)
          json (->json obj)
          rec #js {"realm" realm "type" type "id" id "mode" (name mode) "obj" json
                   "created" (if existing (gobj/get existing "created") t) "updated" t}]
      (await (index! tx k (<-json json)))
      (await (req->p (.put objects rec)))
      (when (= mode :append-only)
        (let [revs (.objectStore tx "revisions")
              n (await (req->p (.count revs (rev-range k))))]
          (await (req->p (.put revs #js {"realm" realm "type" type "id" id
                                         "rev" (inc n) "obj" json "ts" t})))))
      (rec->entry rec))))

(defn- ^:async put-all [^js tx entries]
  (loop [[[k obj opts] & more] entries acc []]
    (if k
      (recur more (conj acc (await (put-one tx k obj opts))))
      acc)))

(defn- ^:async delete-one [^js tx k]
  (let [objects (.objectStore tx "objects")]
    (if-not (await (req->p (.get objects (k->arr k))))
      false
      (do (await (req->p (.delete (.objectStore tx "revisions") (rev-range k))))
          (await (unindex! tx k))
          (await (req->p (.delete objects (k->arr k))))
          true))))

;;; history / types / referrers / search

(defn- ^:async history-entries [^js tx k]
  (when-let [current (await (req->p (.get (.objectStore tx "objects") (k->arr k))))]
    (if (= "append-only" (gobj/get current "mode"))
      (->> (await (req->p (.getAll (.objectStore tx "revisions") (rev-range k))))
           array-seq
           (sort-by #(gobj/get % "rev") >)
           (mapv #(rec->entry % "append-only")))
      [(rec->entry current)])))

(defn- ^:async type-counts [^js tx realm]
  (->> (await (req->p (.getAll (.objectStore tx "objects"))))
       array-seq
       (filter #(or (nil? realm) (= realm (gobj/get % "realm"))))
       (map #(gobj/get % "type"))
       frequencies
       (into (sorted-map))))

(defn- ^:async fetch-entries
  "Entries for `keys`, skipping missing objects."
  [^js tx keys]
  (let [objects (.objectStore tx "objects")]
    (loop [[k & more] keys acc []]
      (if k
        (let [r (await (req->p (.get objects (k->arr k))))]
          (recur more (cond-> acc r (conj (rec->entry r)))))
        acc))))

(defn- ^:async referrer-entries [^js tx uri {:keys [attr type]}]
  (let [rows (->> (await (req->p (.getAll (.index (.objectStore tx "attrs") "value") uri)))
                  array-seq
                  (filter #(or (nil? attr) (= (kstr attr) (gobj/get % "attr"))))
                  (filter #(or (nil? type) (= (name type) (gobj/get % "type"))))
                  (map (juxt rec-key #(gobj/get % "attr")))
                  distinct)
        entries (await (fetch-entries tx (map first rows)))
        by-uri (into {} (map (juxt :uri identity)) entries)]
    (->> rows
         (keep (fn [[k a]] (some-> (by-uri (uri/format-uri k)) (assoc :attr (keyword a)))))
         (sort-by (comp :updated :meta) >)
         vec)))

(defn- query-tokens [q] (vec (mapcat tokens (re-seq #"\S+" (str q)))))

(defn- prefix-hit? [qtoks tok] (some #(str/starts-with? tok %) qtoks))

(defn snippet
  "Up to 16 words of `text` around the first hit, hits wrapped in «», elisions marked …."
  [text qtoks]
  (let [re (word-re)
        ms (loop [acc []]
             (if-let [m (.exec re text)]
               (let [w (aget m 0) start (.-index m)]
                 (recur (conj acc {:start start :end (+ start (count w)) :hit? (prefix-hit? qtoks (fold w))})))
               acc))
        n 16
        first-hit (or (first (keep-indexed (fn [i m] (when (:hit? m) i)) ms)) 0)
        lo (max 0 (min (- first-hit 3) (- (count ms) n)))
        hi (min (count ms) (+ lo n))
        window (subvec ms lo hi)
        from (if (zero? lo) 0 (:start (first window)))
        to (if (= hi (count ms)) (count text) (:end (peek window)))]
    (loop [[m & more] window pos from out (if (pos? lo) "…" "")]
      (if-not m
        (str out (subs text pos to) (when (< hi (count ms)) "…"))
        (recur more (:end m)
               (str out (subs text pos (:start m))
                    (if (:hit? m) (str "«" (subs text (:start m) (:end m)) "»") (subs text (:start m) (:end m)))))))))

(defn- ^:async search-entries [^js tx query {:keys [type realm limit raw?]}]
  (when raw?
    (throw (ex-info "raw? search (FTS5 syntax) is not supported by the IndexedDB backend" {:query query})))
  (let [qtoks (query-tokens query)]
    (if (empty? qtoks)
      []
      (let [idx (.index (.objectStore tx "fts") "tokens")
            hits (loop [[t & more] qtoks acc nil]
                   (if (and t (or (nil? acc) (seq acc)))
                     (let [recs (array-seq (await (req->p (.getAll idx (js/IDBKeyRange.bound t (str t "￿"))))))
                           m (into {} (map (juxt (comp uri/format-uri rec-key) identity)) recs)]
                       (recur more (if acc (select-keys m (keys acc)) m)))
                     acc))
            recs (->> (vals hits)
                      (filter #(or (nil? type) (= (name type) (gobj/get % "type"))))
                      (filter #(or (nil? realm) (= realm (gobj/get % "realm")))))
            entries (await (fetch-entries tx (map rec-key recs)))
            by-uri (into {} (map (juxt :uri identity)) entries)]
        (->> recs
             (keep (fn [r]
                     (let [text (gobj/get r "text")
                           rank (- (count (filter #(prefix-hit? qtoks %) (tokens text))))]
                       (some-> (by-uri (uri/format-uri (rec-key r)))
                               (assoc :rank rank :snippet (snippet text qtoks))))))
             (sort-by :rank)
             (#(if limit (take limit %) %))
             vec)))))

(def ^:private all-stores ["objects" "revisions" "attrs" "fts"])

(defrecord IdbStore [^js db]
  store/Store
  (put* [_ k obj opts] (run-tx db all-stores "readwrite" #(put-one % k obj opts)))
  (put-many* [_ entries] (run-tx db all-stores "readwrite" #(put-all % entries)))
  (get* [_ k]
    (run-tx db ["objects"] "readonly"
            #(.then (req->p (.get (.objectStore % "objects") (k->arr k)))
                    (fn [r] (some-> r rec->entry)))))
  (find* [_ type opts] (run-tx db ["objects" "attrs"] "readonly" #(find-entries % (name type) opts)))
  (delete* [_ k] (run-tx db all-stores "readwrite" #(delete-one % k)))
  (history* [_ k] (run-tx db ["objects" "revisions"] "readonly" #(history-entries % k)))
  (types* [_ realm] (run-tx db ["objects"] "readonly" #(type-counts % realm)))
  (referrers* [_ uri opts] (run-tx db ["objects" "attrs"] "readonly" #(referrer-entries % uri opts)))
  (search* [_ query opts] (run-tx db ["objects" "fts"] "readonly" #(search-entries % query opts))))

(defn- create-schema! [^js db]
  (let [objects (.createObjectStore db "objects" #js {:keyPath #js ["realm" "type" "id"]})]
    (.createIndex objects "type_updated" #js ["type" "updated"])
    (.createIndex objects "type_realm_updated" #js ["type" "realm" "updated"]))
  (.createObjectStore db "revisions" #js {:keyPath #js ["realm" "type" "id" "rev"]})
  (let [attrs (.createObjectStore db "attrs" #js {:autoIncrement true})]
    (.createIndex attrs "key" #js ["realm" "type" "id"])
    (.createIndex attrs "type_attr_value" #js ["type" "attr" "v"])
    (.createIndex attrs "value" "v"))
  (let [fts (.createObjectStore db "fts" #js {:keyPath #js ["realm" "type" "id"]})]
    (.createIndex fts "tokens" "tokens" #js {:multiEntry true})))

(defn open
  "Promise of a store on IndexedDB database `name`, created if needed."
  [name]
  (let [req (.open js/indexedDB name db-version)]
    (set! (.-onupgradeneeded req)
          (fn [^js e] (when (< (.-oldVersion e) 1) (create-schema! (.-result req)))))
    (.then (req->p req) ->IdbStore)))
