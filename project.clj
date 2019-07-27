(defproject com.brunobonacci/safely "0.5.0-alpha7-SNAPSHOT"
  :description "Safely it's a Clojure's circuit-breaker library for handling retries in code executions."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "0.3.0"]
                 [defun "0.2.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [samsara/trackit-core "0.9.0"]
                 [amalloy/ring-buffer "1.3.0"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:resource-paths ["dev-resources"]
                   :dependencies [[org.clojure/test.check "0.9.0"]
                                  [midje "1.9.8"]
                                  [org.slf4j/slf4j-log4j12 "1.7.26"]]
                   :plugins [[lein-midje "3.2.1"]]}}

  :aliases {"test" ["do" "clean," "midje"]}
  )
