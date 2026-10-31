(ns hyperphor.tunamelt.sqlite
  "SQLite backend. Objects are JSON text; attribute find uses json_extract (a scan within type)."
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
      PRIMARY KEY (realm, type, id, rev))"])

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

(defn- attr-clause
  "SQL fragment + params for top-level attribute equality."
  [[k v]]
  (let [path (str "$.\"" (str/replace (kstr k) "\"" "\\\"") "\"")]
    (cond (nil? v)     ["json_extract(obj, ?) IS NULL" [path]]
          (boolean? v) ["json_extract(obj, ?) = ?" [path (if v 1 0)]]
          (or (keyword? v) (uuid? v)) ["json_extract(obj, ?) = ?" [path (kstr v)]]
          (or (string? v) (number? v)) ["json_extract(obj, ?) = ?" [path v]]
          :else (throw (ex-info "find :attrs supports scalar values only" {:attr k :value v})))))

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
    (let [clauses (cond-> [["type = ?" [type]]]
                    realm  (conj ["realm = ?" [realm]])
                    since  (conj ["updated >= ?" [(->millis since)]])
                    until  (conj ["updated < ?" [(->millis until)]])
                    :always (into (map attr-clause attrs)))
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
        [(row->entry current)]))))

(defn open
  "Open (creating if needed) a SQLite store at file path."
  [path]
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (str path)})]
    (jdbc/execute-one! ds ["PRAGMA journal_mode=WAL"])
    (doseq [stmt ddl] (jdbc/execute-one! ds [stmt]))
    (->SqliteStore ds)))
