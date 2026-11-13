(ns hyperphor.tunamelt.codec
  "JSON encoding shared by all backends. Keyword keys and values keep their namespace
  (\"block/uuid\"); on read, keys come back as keywords and values as strings."
  #?(:clj (:require [clojure.data.json :as json])))

(defn kstr [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn jsonable
  "obj with keys as strings and keywords/uuids (and, in cljs, insts) as strings."
  [x]
  (cond (map? x)     (into {} (map (fn [[k v]] [(kstr k) (jsonable v)])) x)
        (keyword? x) (kstr x)
        (uuid? x)    (str x)
        #?@(:cljs [(inst? x) (.toISOString x)])
        (coll? x)    (mapv jsonable x)
        :else        x))

(defn ->json [obj]
  #?(:clj  (json/write-str (jsonable obj))
     :cljs (js/JSON.stringify (clj->js (jsonable obj)))))

(defn <-json [s]
  #?(:clj  (json/read-str s :key-fn keyword)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))

(defn roundtrip
  "v as it would come back from get (keywords, uuids, insts → strings)."
  [v]
  (:v (<-json (->json {:v v}))))
