(defproject com.brunobonacci/safely "0.1.0-SNAPSHOT"
  :description "A library to handle execution errors and automatic retries."
  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [expectations "2.1.3"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [defun "0.2.0-RC"]]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.8.2"]]}})
