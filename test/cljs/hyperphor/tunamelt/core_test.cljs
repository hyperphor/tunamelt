(ns hyperphor.tunamelt.core-test
  "Port of the JVM core tests to the IndexedDB backend (fake-indexeddb under node)."
  (:require ["fake-indexeddb/auto"]
            [clojure.test :refer [deftest is testing async]]
            [hyperphor.tunamelt.core :as t]
            [hyperphor.tunamelt.idb :as idb]
            [hyperphor.tunamelt.uri :as uri]))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- run
  "Run async body f against a fresh database, then call done."
  [done f]
  (t/configure! {:store (str "test-" (random-uuid)) :realm "test"})
  (-> (js/Promise.resolve)
      (.then f)
      (.catch (fn [e] (is (nil? e) (str "unexpected error: " (ex-message e) " " (ex-data e)))))
      (.finally done)))

(defn- ^:async error-of
  "Message of p's rejection, or nil if it resolved."
  [p]
  (try (await p) nil (catch :default e (ex-message e))))

(defn- ids [entries] (set (map (comp :id :meta) entries)))

(deftest uri-test
  (is (= {:realm "okc" :type "prompt" :id "abc/def"} (uri/parse-uri "hyperphor://okc/prompt/abc/def")))
  (is (= "hyperphor://okc/prompt/x" (uri/format-uri {:realm "okc" :type "prompt" :id "x"})))
  (is (thrown? ExceptionInfo (uri/parse-uri "nope"))))

(deftest put-get-test
  (async done
    (run done
         (^:async fn []
          (let [u "hyperphor://okc/prompt/p1"
                obj {:text "hello" :n 3 :tags ["a" "b"] :nested {:x 1}}]
            (await (t/put u obj))
            (is (= obj (await (t/get u))))
            (await (t/put u {:text "bye"}))
            (is (= {:text "bye"} (await (t/get u))))
            (is (= :mutable (get-in (await (t/get-entry u)) [:meta :tunamelt/mode])))
            (is (nil? (await (t/get "hyperphor://okc/prompt/missing")))))))))

(deftest generated-id-test
  (async done
    (run done
         (^:async fn []
          (let [{:keys [uri meta]} (await (t/put "dialog" {:turns []}))]
            (is (re-matches #"hyperphor://test/dialog/[0-9a-f-]{36}" uri))
            (is (= "test" (:realm meta)))
            (is (= {:turns []} (await (t/get uri)))))))))

(deftest keyword-roundtrip-test
  (async done
    (run done
         (^:async fn []
          (let [u "hyperphor://okc/block/k"]
            (await (t/put u {:block/uuid "u1" :status :done :role :user/admin}))
            (testing "namespaced keys survive; keyword values come back as strings"
              (is (= {:block/uuid "u1" :status "done" :role "user/admin"} (await (t/get u))))))))))

(deftest modes-test
  (async done
    (run done
         (^:async fn []
          (let [u "hyperphor://okc/prompt/versioned"]
            (await (t/put u {:v 1} {:mode :append-only}))
            (await (t/put u {:v 2}))
            (await (t/put u {:v 3}))
            (is (= {:v 3} (await (t/get u))))
            (is (= [{:v 3} {:v 2} {:v 1}] (map :obj (await (t/history u)))))
            (is (= [3 2 1] (map (comp :rev :meta) (await (t/history u)))))
            (testing "mode can't change"
              (is (re-find #"Cannot change mode" (str (await (error-of (t/put u {:v 4} {:mode :mutable})))))))
            (testing "mutable history is just current"
              (await (t/put "hyperphor://okc/log/l" {:a 1}))
              (await (t/put "hyperphor://okc/log/l" {:a 2}))
              (is (= [{:a 2}] (map :obj (await (t/history "hyperphor://okc/log/l"))))))
            (testing "delete removes history"
              (is (true? (await (t/delete u))))
              (is (nil? (await (t/get u))))
              (is (empty? (await (t/history u))))
              (is (false? (await (t/delete u))))))))))

(deftest find-test
  (async done
    (run done
         (^:async fn []
          (await (t/put "hyperphor://okc/log/a" {:sql "select 1" :ok true :n 1}))
          (await (sleep 5))
          (let [t0 (js/Date.)]
            (await (sleep 5))
            (await (t/put "hyperphor://okc/log/b" {:sql "select 2" :ok false :n 2}))
            (await (sleep 2))
            (await (t/put "hyperphor://ammdi/log/c" {:sql "select 3" :ok true :n 2}))
            (await (t/put "hyperphor://okc/prompt/d" {:n 2}))
            (is (= #{"a" "b" "c"} (ids (await (t/find "log")))))
            (is (= "c" (-> (await (t/find :log)) first :meta :id)) "newest first")
            (is (= #{"a" "b"} (ids (await (t/find "log" {:realm "okc"})))))
            (is (= #{"b" "c"} (ids (await (t/find "log" {:since t0})))))
            (is (= #{"a"} (ids (await (t/find "log" {:until t0})))))
            (is (= #{"a" "c"} (ids (await (t/find "log" {:attrs {:ok true}})))))
            (is (= #{"b"} (ids (await (t/find "log" {:attrs {:ok false :n 2}})))))
            (is (= 1 (count (await (t/find "log" {:limit 1})))))
            (is (= #{"a"} (ids (await (t/find "log" {:attrs {:n [1]}})))))
            (is (some? (await (error-of (t/find "log" {:attrs {:n {:x 1}}}))))))))))

(deftest scale-test
  (async done
    (run done
         (^:async fn []
          (let [n 2000
                start (js/Date.now)]
            (await (t/put-many (for [i (range n)]
                                 [(str "hyperphor://exobrain/block/" i)
                                  {:block/uuid (str "uuid-" i)
                                   :block/page (str "page-" (mod i 50))
                                   :block/content (str "content " i)}])))
            (println "put-many" n "blocks:" (- (js/Date.now) start) "ms (fake-indexeddb)")
            (is (= n (count (await (t/find "block" {:realm "exobrain"})))))
            (let [start (js/Date.now)
                  hits (await (t/find "block" {:attrs {:block/uuid "uuid-1234"}}))]
              (println "find by :block/uuid:" (- (js/Date.now) start) "ms")
              (is (= ["hyperphor://exobrain/block/1234"] (map :uri hits))))
            (is (= 40 (count (await (t/find "block" {:attrs {:block/page "page-7"}}))))))))))

(deftest inst-and-scheme-test
  (async done
    (run done
         (^:async fn []
          (await (t/put "hyperphor://okc/log/when" {:at #inst "2026-10-31T00:00:00.000-00:00"}))
          (is (string? (:at (await (t/get "hyperphor://okc/log/when")))))
          (is (some? (await (error-of (t/put "http://x/log/1" {})))))))))

(deftest types-test
  (async done
    (run done
         (^:async fn []
          (is (empty? (await (t/types))))
          (await (t/put "hyperphor://okc/log/a" {}))
          (await (t/put "hyperphor://okc/log/b" {}))
          (await (t/put "hyperphor://ammdi/log/c" {}))
          (await (t/put "hyperphor://ammdi/block/d" {}))
          (is (= ["block" "log"] (await (t/types))))
          (is (= {"block" 1 "log" 3} (await (t/types {:counts? true}))))
          (is (= ["log"] (await (t/types {:realm "okc"}))))
          (is (= {"block" 1 "log" 1} (await (t/types {:realm "ammdi" :counts? true}))))))))

(deftest multi-valued-attrs-test
  (async done
    (run done
         (^:async fn []
          (await (t/put "hyperphor://okc/post/1" {:tags ["a" "b"] :n [1 2]}))
          (await (t/put "hyperphor://okc/post/2" {:tags ["b" "c"]}))
          (await (t/put "hyperphor://okc/post/3" {:tags "a"}))
          (is (= #{"1" "3"} (ids (await (t/find "post" {:attrs {:tags "a"}})))) "scalar matches any element")
          (is (= #{"1" "2"} (ids (await (t/find "post" {:attrs {:tags "b"}})))))
          (is (= #{"1"} (ids (await (t/find "post" {:attrs {:tags ["a" "b"]}})))) "vector = contains all")
          (is (= #{"1"} (ids (await (t/find "post" {:attrs {:n 2.0}})))) "numeric equality across int/float")
          (is (= #{"2" "3"} (ids (await (t/find "post" {:attrs {:n nil}})))) "nil = attr absent")
          (is (= ["a" "b"] (:tags (await (t/get "hyperphor://okc/post/1")))) "array order preserved")))))

(deftest reindex-on-put-and-delete-test
  (async done
    (run done
         (^:async fn []
          (let [u "hyperphor://okc/post/x"]
            (await (t/put u {:tag "a"}))
            (is (= #{"x"} (ids (await (t/find "post" {:attrs {:tag "a"}})))))
            (await (t/put u {:tag "b"}))
            (is (empty? (await (t/find "post" {:attrs {:tag "a"}}))) "no stale match")
            (is (= #{"x"} (ids (await (t/find "post" {:attrs {:tag "b"}})))))
            (await (t/delete u))
            (is (empty? (await (t/find "post" {:attrs {:tag "b"}})))))))))

(deftest coerced-values-findable-test
  (async done
    (run done
         (^:async fn []
          (let [u "hyperphor://okc/event/e"
                at #inst "2026-10-31T00:00:00.000-00:00"
                id (random-uuid)]
            (await (t/put u {:status :done :role :user/admin :at at :ref id}))
            (let [obj (await (t/get u))]
              (doseq [k [:status :role :at :ref]]
                (is (= #{"e"} (ids (await (t/find "event" {:attrs {k (get obj k)}})))) (str "value from get, " k))))
            (is (= #{"e"} (ids (await (t/find "event" {:attrs {:status :done :role :user/admin :at at :ref id}}))))
                "original values too"))))))

(deftest relations-test
  (async done
    (run done
         (^:async fn []
          (let [page "hyperphor://g/page/eliza"
                b1   "hyperphor://g/block/1"
                b2   "hyperphor://g/block/2"]
            (await (t/put page {:name "ELIZA"}))
            (await (t/put b1 {:content "about [[ELIZA]]" :page "hyperphor://g/page/weizenbaum" :refs [page]}))
            (await (t/put b2 {:content "child" :parent b1 :refs [page "hyperphor://g/page/other"]}))
            (is (= {:page ["hyperphor://g/page/weizenbaum"] :refs [page]} (await (t/refs b1))))
            (is (= {:parent [b1] :refs [page "hyperphor://g/page/other"]} (await (t/refs b2))))
            (is (= #{[b1 :refs] [b2 :refs]} (set (map (juxt :uri :attr) (await (t/referrers page))))))
            (is (= [b2] (map :uri (await (t/referrers b1 {:attr :parent})))))
            (is (empty? (await (t/referrers b1 {:attr :refs}))))
            (is (empty? (await (t/referrers page {:type "page"})))))))))

(defn- ^:async hits [q & [opts]] (ids (await (t/search q opts))))

(deftest search-test
  (async done
    (run done
         (^:async fn []
          (await (t/put "hyperphor://g/block/1" {:content "Heideggerian AI, per Dreyfus" :page "hyperphor://g/page/ai"}))
          (await (t/put "hyperphor://g/block/2" {:content "Café notes on Heidegger" :tags ["phenomenology"]}))
          (await (t/put "hyperphor://g/page/ai" {:name "AI winter"}))
          (await (t/put "hyperphor://h/block/3" {:content "unrelated" :n 3}))
          (is (= #{"1" "2"} (await (hits "heidegger"))) "prefix match, case-insensitive")
          (is (= #{"1"} (await (hits "heidegger dreyfus"))) "all words required")
          (is (= #{"2"} (await (hits "cafe"))) "diacritics folded")
          (is (= #{"2"} (await (hits "phenomenology"))) "array elements indexed")
          (is (empty? (await (hits "hyperphor"))) "URI values not indexed")
          (is (= #{"ai" "1"} (await (hits "ai"))))
          (is (= #{"ai"} (await (hits "ai" {:type "page"}))))
          (is (= #{"3"} (await (hits "unrelated" {:realm "h"}))))
          (is (empty? (await (hits "unrelated" {:realm "g"}))))
          (is (= 1 (count (await (t/search "ai" {:limit 1})))))
          (let [r (first (await (t/search "dreyfus")))]
            (is (= "hyperphor://g/block/1" (:uri r)))
            (is (= "Heideggerian AI, per «Dreyfus»" (:snippet r)))
            (is (number? (:rank r))))
          (testing "awkward input"
            (is (= [] (await (t/search "  "))))
            (is (empty? (await (hits "C++ don't -x \"q"))))
            (is (= (await (hits "heidegger")) (await (hits "heidegger / —"))) "punctuation-only words ignored")
            (is (= [] (await (t/search "- /"))))
            (is (re-find #"not supported" (str (await (error-of (t/search "x" {:raw? true})))))))))))

(deftest search-tracks-writes-test
  (async done
    (run done
         (^:async fn []
          (await (t/put "hyperphor://g/note/a" {:text "alpha"}))
          (await (t/put "hyperphor://g/note/a" {:text "beta"}))
          (is (empty? (await (hits "alpha"))) "re-put replaces old text")
          (is (= #{"a"} (await (hits "beta"))))
          (await (t/delete "hyperphor://g/note/a"))
          (is (empty? (await (hits "beta"))))
          (await (t/put "hyperphor://g/log/l" {:text "first"} {:mode :append-only}))
          (await (t/put "hyperphor://g/log/l" {:text "second"}))
          (is (empty? (await (hits "first"))) "append-only: latest revision only")
          (is (= #{"l"} (await (hits "second"))))))))

(deftest snippet-test
  (let [text "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen target nineteen twenty"
        s (idb/snippet text ["target"])]
    (is (= "…six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen «target» nineteen twenty" s)
        "16-word window, shifted back when the hit is near the end")
    (is (= "«one» two" (idb/snippet "one two" ["one"])) "no ellipses when the text fits")))
