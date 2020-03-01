(defproject com.brunobonacci/safely "0.5.0-alpha8"
  :description "Safely it's a Clojure's circuit-breaker library for handling retries in code executions."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "1.0.0"]
                 [defun "0.3.1"]
                 [org.clojure/tools.logging "1.0.0"]
                 [samsara/trackit-core "0.9.3"]
                 [amalloy/ring-buffer "1.3.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:resource-paths ["dev-resources"]
                   :dependencies [[org.clojure/test.check "1.0.0"]
                                  [midje "1.9.9"]
                                  [org.slf4j/slf4j-log4j12 "1.7.30"]]
                   :plugins [[lein-midje "3.2.2"]]}

             :clj18  {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :clj19  {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :clj110 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             }

  :aliases {"test" ["do" "clean," "midje"]
            "build-all"  ["with-profile" "+clj18:+clj19:+clj110" "do" "clean," "check," "midje," "jar"]}
  )
