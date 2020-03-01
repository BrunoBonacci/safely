(defproject web-api "0.1.0"
  :description "Example of safely usage for web apis"

  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :main safely.examples.web-api.core

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.6.1"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-jetty-adapter "1.6.3"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-json "0.4.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [com.brunobonacci/safely "0.5.0"]
                 [samsara/trackit-influxdb "0.8.0"
                  :exclusions [org.slf4j/log4j-over-slf4j
                               ch.qos.logback/logback-classic]]])
