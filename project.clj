(defproject com.brunobonacci/safely "0.2.0"
  :description "safely it's a Clojure library for exception handling
  and offers a elegant declarative approach to the error management."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [defun "0.2.0-RC"]
                 [com.taoensso/timbre "4.1.4"]
                 [samsara/trackit "0.2.2"]]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.8.2"]
                                  [expectations "2.1.3"]]
                   :plugins [[lein-expectations "0.0.8"]]}})
