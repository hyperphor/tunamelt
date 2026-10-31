(ns hyperphor.tunamelt.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.tunamelt.core :as t]
            [hyperphor.tunamelt.uri :as uri]))

(defn- temp-db-fixture [f]
  (let [file (java.io.File/createTempFile "tunamelt-test" ".db")]
    (try
      (t/configure! {:store (str file) :realm "test"})
      (f)
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
    (is (thrown? clojure.lang.ExceptionInfo (t/find "log" {:attrs {:n [1]}})))))

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
