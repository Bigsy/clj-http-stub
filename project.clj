(defproject org.clojars.bigsy/clj-http-stub "0.1.3"
  :description "Helper for faking clj-http requests in testing"
  :url "https://github.com/Bigsy/clj-http-stub"
  :license {:name "MIT License"
            :url  "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :sign-releases false}]]
  :dependencies [[org.clojure/math.combinatorics "0.3.0"]
                 [robert/hooke "1.3.0"]
                 [clj-http "3.13.0"]
                 [ring/ring-codec "1.2.0"]]
  :aliases {"test-lat" ["with-profile" "clj-lat,1.10:clj-lat,1.11:clj-lat,1.12" "test"]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.12.0"]
                                 [hashp "0.2.2"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}

             :clj-lat {:dependencies [[clj-http "3.13.0"]]}})