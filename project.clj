(defproject com.brunobonacci/safely "1.1.0-SNAPSHOT"
  :description "Safely is a Clojure's circuit-breaker library for handling retries in an elegant declarative way."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.12.4" :scope "provided"]
                 [org.clojure/core.match "1.1.1"]
                 [defun "0.4.0"]
                 [org.clojure/tools.logging "1.3.1"]
                 [com.brunobonacci/mulog "0.10.1"]
                 [amalloy/ring-buffer "1.3.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:resource-paths ["dev-resources"]
                   :dependencies [[org.clojure/test.check "1.1.1"]
                                  [midje "1.10.10"]
                                  [org.apache.logging.log4j/log4j-slf4j-impl "2.20.0"]
                                  [com.brunobonacci/mulog-zipkin "0.10.1"]]
                   :plugins [[lein-midje "3.2.2"]]}

             :clj18  {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :clj19  {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :clj110 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :clj111 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :clj112 {:dependencies [[org.clojure/clojure "1.12.4"]]}
             }

  :aliases {"test" ["do" "clean," "midje"]
            "build-all"  ["with-profile" "+clj18:+clj19:+clj110:+clj111:+clj112"
                          "do" "clean," "check," "test," "jar"]}
  )
