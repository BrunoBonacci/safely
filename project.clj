(defproject com.brunobonacci/safely "0.3.0"
  :description "safely it's a Clojure library for exception handling
  and offers a elegant declarative approach to the error management."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [defun "0.2.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [samsara/trackit-core "0.5.0"]
                 [amalloy/ring-buffer "1.2.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]
                                  [expectations "2.1.9"]]
                   :plugins [[lein-expectations "0.0.8"]]}})
