(defproject com.brunobonacci/safely "0.7.0-SNAPSHOT"
  :description "Safely is a Clojure's circuit-breaker library for handling retries in an elegant declarative way."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.12.0" :scope "provided"]
                 [org.clojure/core.match "1.1.0"]
                 [defun "0.4.0"]
                 [org.clojure/tools.logging "1.3.0"]
                 [com.brunobonacci/mulog "0.9.0"]
                 [amalloy/ring-buffer "1.3.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:resource-paths ["dev-resources"]
                   :dependencies [[org.clojure/test.check "1.1.1"]
                                  [midje "1.10.10"]
                                  [org.slf4j/slf4j-log4j12 "1.7.30"]
                                  [com.brunobonacci/mulog-zipkin "0.9.0"]]
                   :plugins [[lein-midje "3.2.2"]]}

             :clj18  {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :clj19  {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :clj110 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :clj111 {:dependencies [[org.clojure/clojure "1.11.1"]]}
             :clj112 {:dependencies [[org.clojure/clojure "1.12.0"]]}
             }

  :aliases {"test" ["do" "clean," "midje"]
            "build-all"  ["with-profile" "+clj18:+clj19:+clj110:+clj111:+clj112"
                          "do" "clean," "check," "midje," "jar"]}
  )
