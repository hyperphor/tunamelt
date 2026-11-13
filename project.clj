(defproject com.hyperphor/tunamelt "0.1.0"
  :description "Schemaless persistence layer for hand-made objects"
  :url "https://github.com/hyperphor/tunamelt"
  :license {:name "Apache 2 License"
            :url "https://opensource.org/licenses/Apache-2.0"}
  :deploy-repositories [["clojars" {:sign-releases false
                                    :username :env/clojars_username
                                    :password :env/clojars_password}]]
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [com.hyperphor/multitool "0.3.4"]
                 [environ "1.2.0"]
                 [org.clojure/data.json "2.5.2"]
                 [com.github.seancorfield/next.jdbc "1.3.1048"]
                 [org.xerial/sqlite-jdbc "3.46.1.3"]]
  :source-paths ["src/clj" "src/cljc" "src/cljs"]
  :test-paths ["test/clj"]
  :jvm-opts ["--enable-native-access=ALL-UNNAMED"])
