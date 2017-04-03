(defproject tcc-fetch "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [analytics-clj "0.3.0"]
                 [log4j "1.2.16"]
                 [cheshire "5.7.0"]
                 [http-kit "2.2.0"]]
  :main ^:skip-aot tcc-fetch.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
