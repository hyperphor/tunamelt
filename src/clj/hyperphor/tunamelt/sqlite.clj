(ns hyperphor.tunamelt.sqlite
  "SQLite backend. Objects are JSON text (source of truth for get); top-level attributes are
  also indexed in an attrs table for find."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [hyperphor.tunamelt.store :as store]
            [hyperphor.tunamelt.uri :as uri]))

;;; JSON encoding. Keyword keys and values keep their namespace ("block/uuid");
;;; on read, keys come back as keywords, values as strings.

(defn- kstr [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- jsonable [x]
  (cond (map? x)     (into {} (map (fn [[k v]] [(kstr k) (jsonable v)])) x)
        (keyword? x) (kstr x)
        (uuid? x)    (str x)
        (coll? x)    (mapv jsonable x)
        :else        x))

(defn- ->json [obj] (json/write-str (jsonable obj)))
(defn- <-json [s] (json/read-str s :key-fn keyword))

(defn- now [] (System/currentTimeMillis))

(defn- ->millis [t]
  (cond (nil? t)                     nil
        (number? t)                  (long t)
        (instance? java.util.Date t) (.getTime ^java.util.Date t)
        (instance? java.time.Instant t) (.toEpochMilli ^java.time.Instant t)
        :else (throw (ex-info "Unsupported time value" {:value t}))))

;;; Schema

(def ^:private ddl
  ["CREATE TABLE IF NOT EXISTS objects (
      realm TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL,
      mode TEXT NOT NULL, obj TEXT NOT NULL,
      created INTEGER NOT NULL, updated INTEGER NOT NULL,
      PRIMARY KEY (realm, type, id))"
   "CREATE INDEX IF NOT EXISTS objects_type_updated ON objects (type, updated DESC)"
   "CREATE INDEX IF NOT EXISTS objects_type_realm_updated ON objects (type, realm, updated DESC)"
   "CREATE TABLE IF NOT EXISTS revisions (
      realm TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL,
      rev INTEGER NOT NULL, obj TEXT NOT NULL, ts INTEGER NOT NULL,
      PRIMARY KEY (realm, type, id, rev))"
   ;; Derived index of top-level attribute values: one row per scalar (or per array element).
   "CREATE TABLE IF NOT EXISTS attrs (
      realm TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, attr TEXT NOT NULL,
      v_text TEXT, v_num NUMERIC, v_bool INTEGER)"
   "CREATE INDEX IF NOT EXISTS attrs_key ON attrs (realm, type, id)"
   "CREATE INDEX IF NOT EXISTS attrs_text ON attrs (type, attr, v_text)"
   "CREATE INDEX IF NOT EXISTS attrs_num ON attrs (type, attr, v_num)"
   "CREATE INDEX IF NOT EXISTS attrs_bool ON attrs (type, attr, v_bool)"])

(def ^:private schema-version 1)

(def ^:private rs-opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- row->entry [{:keys [realm type id mode obj created updated ts rev]}]
  (let [k {:realm realm :type type :id id}]
    {:uri  (uri/format-uri k)
     :obj  (<-json obj)
     :meta (cond-> (assoc k :tunamelt/mode (keyword mode))
             created (assoc :created created)
             updated (assoc :updated updated)
             ts      (assoc :updated ts)
             rev     (assoc :rev rev))}))

(defn- key-params [{:keys [realm type id]}] [realm type id])

(defn- get-row [conn k]
  (jdbc/execute-one! conn (into ["SELECT * FROM objects WHERE realm=? AND type=? AND id=?"]
                                (key-params k))
                     rs-opts))

;;; Attribute index. Rows are built from the JSON round-trip of the object (what get returns),
;;; so values read back from get always match in find.

(defn- roundtrip
  "v as it would come back from get (keywords, uuids, insts → strings)."
  [v]
  (:v (<-json (->json {:v v}))))

(defn- value-col
  "[column value] for a round-tripped scalar, or nil if not indexable."
  [v]
  (cond (string? v)  ["v_text" v]
        (boolean? v) ["v_bool" (if v 1 0)]
        (instance? clojure.lang.BigInt v) ["v_num" (if (< (.bitLength (biginteger v)) 64) (long v) (double v))]
        (decimal? v) ["v_num" (double v)]
        (number? v)  ["v_num" v]))

(defn- attr-rows
  "Index rows [attr text num bool] for top-level scalars and arrays of scalars of a JSON object."
  [json-obj]
  (when (map? json-obj)
    (for [[k v] json-obj
          x (distinct (if (sequential? v) v [v]))
          :let [[col val] (value-col x)]
          :when col]
      [(kstr k)
       (when (= col "v_text") val)
       (when (= col "v_num") val)
       (when (= col "v_bool") val)])))

(defn- index-attrs! [conn k json-obj]
  (jdbc/execute-one! conn (into ["DELETE FROM attrs WHERE realm=? AND type=? AND id=?"] (key-params k)))
  (when-let [rows (seq (attr-rows json-obj))]
    (jdbc/execute-batch! conn "INSERT INTO attrs (realm,type,id,attr,v_text,v_num,v_bool) VALUES (?,?,?,?,?,?,?)"
                         (map #(into (key-params k) %) rows)
                         {})))

(defn- put-one [conn k obj {:keys [mode]}]
  (let [existing (get-row conn k)
        old-mode (some-> existing :mode keyword)
        mode     (or mode old-mode :mutable)
        _        (when-not (#{:mutable :append-only} mode)
                   (throw (ex-info "Unknown tunamelt mode" {:mode mode})))
        _        (when (and old-mode (not= mode old-mode))
                   (throw (ex-info "Cannot change mode of existing object"
                                   {:key k :mode old-mode :requested mode})))
        t        (now)
        json     (->json obj)]
    (index-attrs! conn k (<-json json))
    (jdbc/execute-one! conn (into ["INSERT INTO objects (realm,type,id,mode,obj,created,updated)
                                    VALUES (?,?,?,?,?,?,?)
                                    ON CONFLICT (realm,type,id)
                                    DO UPDATE SET obj=excluded.obj, updated=excluded.updated"]
                                  (concat (key-params k) [(name mode) json t t])))
    (when (= mode :append-only)
      (jdbc/execute-one! conn (into ["INSERT INTO revisions (realm,type,id,rev,obj,ts)
                                      SELECT ?,?,?, COALESCE(MAX(rev),0)+1, ?, ?
                                      FROM revisions WHERE realm=? AND type=? AND id=?"]
                                    (concat (key-params k) [json t] (key-params k)))))
    (row->entry (get-row conn k))))

(defn- attr-clauses
  "SQL fragments + params for one :attrs entry. nil = attr absent; scalar = any value equals;
  vector = contains all."
  [type [k v]]
  (let [attr (kstr k)
        has  (fn [x]
               (let [[col val] (or (value-col (roundtrip x))
                                   (throw (ex-info "find :attrs values must be scalars or vectors of scalars"
                                                   {:attr k :value x})))]
                 [(str "(realm,type,id) IN (SELECT realm,type,id FROM attrs WHERE type=? AND attr=? AND "
                       col "=?)")
                  [type attr val]]))]
    (cond (nil? v)        [["NOT EXISTS (SELECT 1 FROM attrs a WHERE a.realm=objects.realm AND a.type=objects.type
                                         AND a.id=objects.id AND a.attr=?)"
                            [attr]]]
          (map? v)        (throw (ex-info "find :attrs values must be scalars or vectors of scalars"
                                          {:attr k :value v}))
          (sequential? v) (map has v)
          :else           [(has v)])))

(defrecord SqliteStore [ds]
  store/Store
  ;; Writes are serialized in-process; SQLite allows one writer anyway.
  (put* [_ k obj opts]
    (locking ds
      (jdbc/with-transaction [tx ds]
        (put-one tx k obj opts))))

  (put-many* [_ entries]
    (locking ds
      (jdbc/with-transaction [tx ds]
        (mapv (fn [[k obj opts]] (put-one tx k obj opts)) entries))))

  (get* [_ k]
    (some-> (get-row ds k) row->entry))

  (find* [_ type {:keys [realm since until attrs limit]}]
    (let [attr-cs (mapcat (partial attr-clauses type) attrs)
          ;; With a positive attr filter, "+type" stops SQLite walking objects by type,
          ;; so it drives from the attrs index and looks objects up by primary key.
          type-c  (if (some #(str/starts-with? (first %) "(realm,type,id) IN") attr-cs)
                    "+type = ?"
                    "type = ?")
          clauses (cond-> [[type-c [type]]]
                    realm  (conj ["realm = ?" [realm]])
                    since  (conj ["updated >= ?" [(->millis since)]])
                    until  (conj ["updated < ?" [(->millis until)]])
                    :always (into attr-cs))
          sql     (str "SELECT * FROM objects WHERE "
                       (str/join " AND " (map first clauses))
                       " ORDER BY updated DESC"
                       (when limit (str " LIMIT " (long limit))))]
      (mapv row->entry
            (jdbc/execute! ds (into [sql] (mapcat second clauses)) rs-opts))))

  (delete* [_ k]
    (locking ds
     (jdbc/with-transaction [tx ds]
      (jdbc/execute-one! tx (into ["DELETE FROM revisions WHERE realm=? AND type=? AND id=?"]
                                  (key-params k)))
      (jdbc/execute-one! tx (into ["DELETE FROM attrs WHERE realm=? AND type=? AND id=?"]
                                  (key-params k)))
      (pos? (:next.jdbc/update-count
             (jdbc/execute-one! tx (into ["DELETE FROM objects WHERE realm=? AND type=? AND id=?"]
                                         (key-params k))))))))

  (history* [this k]
    (when-let [current (get-row ds k)]
      (if (= "append-only" (:mode current))
        (mapv #(row->entry (assoc % :mode (:mode current)))
              (jdbc/execute! ds (into ["SELECT * FROM revisions WHERE realm=? AND type=? AND id=?
                                        ORDER BY rev DESC"]
                                      (key-params k))
                             rs-opts))
        [(row->entry current)])))

  (types* [_ realm]
    (into (sorted-map)
          (map (juxt :type :n))
          (jdbc/execute! ds (if realm
                              ["SELECT type, COUNT(*) AS n FROM objects WHERE realm=? GROUP BY type" realm]
                              ["SELECT type, COUNT(*) AS n FROM objects GROUP BY type"])
                         rs-opts))))

(defn reindex!
  "Rebuild the attrs index from stored objects."
  [conn]
  (jdbc/execute-one! conn ["DELETE FROM attrs"])
  (doseq [{:keys [realm type id obj]} (jdbc/execute! conn ["SELECT realm,type,id,obj FROM objects"] rs-opts)]
    (index-attrs! conn {:realm realm :type type :id id} (<-json obj))))

(defn open
  "Open (creating or upgrading if needed) a SQLite store at file path."
  [path]
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (str path)})]
    (jdbc/execute-one! ds ["PRAGMA journal_mode=WAL"])
    (jdbc/with-transaction [tx ds]
      (doseq [stmt ddl] (jdbc/execute-one! tx [stmt]))
      ;; Databases from before the attrs table need it backfilled.
      (when (< (:user_version (jdbc/execute-one! tx ["PRAGMA user_version"] rs-opts)) schema-version)
        (reindex! tx)
        (jdbc/execute-one! tx [(str "PRAGMA user_version = " schema-version)])))
    (->SqliteStore ds)))
