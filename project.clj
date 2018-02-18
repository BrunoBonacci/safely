(defproject com.brunobonacci/safely "0.5.0-alpha4"
  :description "safely it's a Clojure library for exception handling
  and offers a elegant declarative approach to the error management."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [defun "0.2.0"]
                 [org.clojure/tools.logging "0.4.0"]
                 [samsara/trackit-core "0.6.0"]
                 [amalloy/ring-buffer "1.2.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:resource-paths ["dev-resources"]
                   :dependencies [[org.clojure/test.check "0.9.0"]
                                  [midje "1.9.0"]
                                  [org.slf4j/slf4j-log4j12 "1.7.25"]]
                   :plugins [[lein-midje "3.2.1"]]}}

  :aliases {"test" ["do" "clean," "midje"]}
  )
