(defproject etl-load "0.1.0"
  :description "Example of safely usage for loading (ETL) large files into a DB."

  :url "https://github.com/BrunoBonacci/safely"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/safely"}

  :main safely.examples.etl-load.main

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.8.0"]
                 [com.brunobonacci/safely "0.7.0-SNAPSHOT"]
                 [amazonica "0.3.117"
                  :exclusions [com.amazonaws/aws-java-sdk
                               com.amazonaws/amazon-kinesis-client]]
                 [com.amazonaws/aws-java-sdk-core "1.11.267"]
                 [com.amazonaws/aws-java-sdk-dynamodb "1.11.267"]
                 [iota "1.1.3"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [progrock "0.1.2"]
                 [com.brunobonacci/mulog "0.4.0-SNAPSHOT"]
                 [com.brunobonacci/mulog-elasticsearch "0.4.0-SNAPSHOT"]
                 [com.brunobonacci/mulog-zipkin "0.4.0-SNAPSHOT"]])
