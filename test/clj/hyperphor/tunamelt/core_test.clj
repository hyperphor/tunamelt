(ns hyperphor.tunamelt.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [hyperphor.tunamelt.core :as t]
            [hyperphor.tunamelt.sqlite :as sqlite]
            [hyperphor.tunamelt.uri :as uri]))

(def ^:dynamic *db-file* nil)

(defn- temp-db-fixture [f]
  (let [file (java.io.File/createTempFile "tunamelt-test" ".db")]
    (try
      (t/configure! {:store (str file) :realm "test"})
      (binding [*db-file* (str file)] (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (.delete (java.io.File. (str file suffix))))))))

(use-fixtures :each temp-db-fixture)

(deftest uri-test
  (is (= {:realm "okc" :type "prompt" :id "abc/def"}
         (uri/parse-uri "hyperphor://okc/prompt/abc/def")))
  (is (= "hyperphor://okc/prompt/x" (uri/format-uri {:realm "okc" :type "prompt" :id "x"})))
  (is (thrown? clojure.lang.ExceptionInfo (uri/parse-uri "nope"))))

(deftest put-get-test
  (let [u "hyperphor://okc/prompt/p1"
        obj {:text "hello" :n 3 :tags ["a" "b"] :nested {:x 1}}]
    (t/put u obj)
    (is (= obj (t/get u)))
    (t/put u {:text "bye"})
    (is (= {:text "bye"} (t/get u)))
    (is (= :mutable (get-in (t/get-entry u) [:meta :tunamelt/mode])))
    (is (nil? (t/get "hyperphor://okc/prompt/missing")))))

(deftest generated-id-test
  (let [{:keys [uri meta]} (t/put "dialog" {:turns []})]
    (is (re-matches #"hyperphor://test/dialog/[0-9a-f-]{36}" uri))
    (is (= "test" (:realm meta)))
    (is (= {:turns []} (t/get uri)))))

(deftest keyword-roundtrip-test
  (let [u "hyperphor://okc/block/k"]
    (t/put u {:block/uuid "u1" :status :done :role :user/admin})
    (testing "namespaced keys survive; keyword values come back as strings"
      (is (= {:block/uuid "u1" :status "done" :role "user/admin"} (t/get u))))))

(deftest modes-test
  (let [u "hyperphor://okc/prompt/versioned"]
    (t/put u {:v 1} {:mode :append-only})
    (t/put u {:v 2})
    (t/put u {:v 3})
    (is (= {:v 3} (t/get u)))
    (is (= [{:v 3} {:v 2} {:v 1}] (map :obj (t/history u))))
    (is (= [3 2 1] (map (comp :rev :meta) (t/history u))))
    (testing "mode can't change"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot change mode"
                            (t/put u {:v 4} {:mode :mutable}))))
    (testing "mutable history is just current"
      (t/put "hyperphor://okc/log/l" {:a 1})
      (t/put "hyperphor://okc/log/l" {:a 2})
      (is (= [{:a 2}] (map :obj (t/history "hyperphor://okc/log/l")))))
    (testing "delete removes history"
      (is (true? (t/delete u)))
      (is (nil? (t/get u)))
      (is (empty? (t/history u)))
      (is (false? (t/delete u))))))

(deftest find-test
  (t/put "hyperphor://okc/log/a" {:sql "select 1" :ok true :n 1})
  (Thread/sleep 5)
  (let [t0 (java.util.Date.)]
    (Thread/sleep 5)
    (t/put "hyperphor://okc/log/b" {:sql "select 2" :ok false :n 2})
    (Thread/sleep 2)
    (t/put "hyperphor://ammdi/log/c" {:sql "select 3" :ok true :n 2})
    (t/put "hyperphor://okc/prompt/d" {:n 2})
    (is (= #{"a" "b" "c"} (set (map (comp :id :meta) (t/find "log")))))
    (is (= "c" (-> (t/find :log) first :meta :id)) "newest first")
    (is (= #{"a" "b"} (set (map (comp :id :meta) (t/find "log" {:realm "okc"})))))
    (is (= #{"b" "c"} (set (map (comp :id :meta) (t/find "log" {:since t0})))))
    (is (= #{"a"} (set (map (comp :id :meta) (t/find "log" {:until t0})))))
    (is (= #{"a" "c"} (set (map (comp :id :meta) (t/find "log" {:attrs {:ok true}})))))
    (is (= #{"b"} (set (map (comp :id :meta) (t/find "log" {:attrs {:ok false :n 2}})))))
    (is (= 1 (count (t/find "log" {:limit 1}))))
    (is (= #{"a"} (set (map (comp :id :meta) (t/find "log" {:attrs {:n [1]}})))))
    (is (thrown? clojure.lang.ExceptionInfo (t/find "log" {:attrs {:n {:x 1}}})))))

(deftest logseq-scale-test
  (let [n 5000
        blocks (for [i (range n)]
                 [(str "hyperphor://exobrain/block/" i)
                  {:block/uuid (str "uuid-" i)
                   :block/page (str "page-" (mod i 50))
                   :block/content (str "content " i)}])
        start (System/currentTimeMillis)]
    (t/put-many blocks)
    (let [elapsed (- (System/currentTimeMillis) start)]
      (println "put-many" n "blocks:" elapsed "ms")
      (is (< elapsed 20000)))
    (is (= n (count (t/find "block" {:realm "exobrain"}))))
    (let [start (System/currentTimeMillis)
          hits (t/find "block" {:attrs {:block/uuid "uuid-4242"}})]
      (println "find by :block/uuid:" (- (System/currentTimeMillis) start) "ms")
      (is (= ["hyperphor://exobrain/block/4242"] (map :uri hits))))
    (is (= 100 (count (t/find "block" {:attrs {:block/page "page-7"}}))))))

(deftest concurrent-put-test
  (->> (range 8)
       (mapv (fn [i] (future (dotimes [j 50]
                               (t/put (str "hyperphor://okc/log/" i "-" j) {:i i})))))
       (run! deref))
  (is (= 400 (count (t/find "log")))))

(deftest inst-and-scheme-test
  (let [u "hyperphor://okc/log/when"]
    (t/put u {:at #inst "2026-10-31T00:00:00.000-00:00"})
    (is (string? (:at (t/get u)))))
  (is (thrown? clojure.lang.ExceptionInfo (t/put "http://x/log/1" {}))))

(deftest types-test
  (is (empty? (t/types)))
  (t/put "hyperphor://okc/log/a" {})
  (t/put "hyperphor://okc/log/b" {})
  (t/put "hyperphor://ammdi/log/c" {})
  (t/put "hyperphor://ammdi/block/d" {})
  (is (= ["block" "log"] (t/types)))
  (is (= {"block" 1 "log" 3} (t/types {:counts? true})))
  (is (= ["log"] (t/types {:realm "okc"})))
  (is (= {"block" 1 "log" 1} (t/types {:realm "ammdi" :counts? true}))))

(defn- ids [type opts] (set (map (comp :id :meta) (t/find type opts))))

(deftest multi-valued-attrs-test
  (t/put "hyperphor://okc/post/1" {:tags ["a" "b"] :n [1 2]})
  (t/put "hyperphor://okc/post/2" {:tags ["b" "c"]})
  (t/put "hyperphor://okc/post/3" {:tags "a"})
  (is (= #{"1" "3"} (ids "post" {:attrs {:tags "a"}})) "scalar matches any element")
  (is (= #{"1" "2"} (ids "post" {:attrs {:tags "b"}})))
  (is (= #{"1"} (ids "post" {:attrs {:tags ["a" "b"]}})) "vector = contains all")
  (is (= #{"1"} (ids "post" {:attrs {:n 2.0}})) "numeric equality across int/float")
  (is (= #{"2" "3"} (ids "post" {:attrs {:n nil}})) "nil = attr absent")
  (is (= ["a" "b"] (:tags (t/get "hyperphor://okc/post/1"))) "array order preserved in get"))

(deftest reindex-on-put-and-delete-test
  (let [u "hyperphor://okc/post/x"]
    (t/put u {:tag "a"})
    (is (= #{"x"} (ids "post" {:attrs {:tag "a"}})))
    (t/put u {:tag "b"})
    (is (empty? (ids "post" {:attrs {:tag "a"}})) "no stale match")
    (is (= #{"x"} (ids "post" {:attrs {:tag "b"}})))
    (t/delete u)
    (is (zero? (:n (jdbc/execute-one! (jdbc/get-datasource {:dbtype "sqlite" :dbname *db-file*})
                                      ["SELECT COUNT(*) AS n FROM attrs"]))))))

(deftest coerced-values-findable-test
  (let [u "hyperphor://okc/event/e"
        at #inst "2026-10-31T00:00:00.000-00:00"
        id (random-uuid)]
    (t/put u {:status :done :role :user/admin :at at :ref id})
    (let [obj (t/get u)]
      (doseq [k [:status :role :at :ref]]
        (is (= #{"e"} (ids "event" {:attrs {k (get obj k)}})) (str "value from get, " k))))
    (is (= #{"e"} (ids "event" {:attrs {:status :done :role :user/admin :at at :ref id}}))
        "original Clojure values too")))

(deftest find-uses-attr-index-test
  (t/put-many (for [i (range 200)] [(str "hyperphor://okc/post/" i) {:tag (str "t" (mod i 10)) :n i}]))
  (let [ds    (jdbc/get-datasource {:dbtype "sqlite" :dbname *db-file*})
        sql   (atom nil)
        _     (with-redefs [jdbc/execute! (let [f jdbc/execute!]
                                            (fn [conn q & more] (reset! sql q) (apply f conn q more)))]
                (is (= 20 (count (t/find "post" {:attrs {:tag "t3"}})))))
        plan  (map #(some (fn [[k v]] (when (= "detail" (name k)) v)) %)
                   (jdbc/execute! ds (update @sql 0 #(str "EXPLAIN QUERY PLAN " %))))]
    (is (some #(re-find #"SEARCH attrs USING INDEX attrs_text" %) plan) (str plan))
    (is (some #(re-find #"SEARCH objects USING INDEX sqlite_autoindex_objects_1" %) plan)
        (str "objects looked up by primary key, not walked by type: " plan))))

(deftest backfill-test
  (t/put "hyperphor://okc/post/old" {:tag "legacy"})
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname *db-file*})]
    (jdbc/execute-one! ds ["DELETE FROM attrs"])
    (jdbc/execute-one! ds ["PRAGMA user_version = 0"]))
  (is (empty? (ids "post" {:attrs {:tag "legacy"}})))
  (t/configure! {:store *db-file*})
  (is (= #{"old"} (ids "post" {:attrs {:tag "legacy"}}))))
