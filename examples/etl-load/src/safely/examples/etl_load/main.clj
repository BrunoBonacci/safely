(ns safely.examples.etl-load.main
  (:gen-class)
  (:require [safely.examples.etl-load.core :refer [DEFAUL-CFG load-records-from-file]]
            [safely.examples.etl-load.gen-file :refer [gen-records]]
            [samsara.trackit :as trackit])
  (:import java.util.concurrent.TimeUnit))



(defn- init-reporting! []
  (trackit/start-reporting!
   {:type                        :influxdb
    ;; disable JVM metrics publishing
    :jvm-metrics                 :none
    ;; how often the stats will be reported to the server
    :reporting-frequency-seconds 10
    ;; riemann host and port
    :host                        (or (System/getenv "INFLUXDB_HOST") "localhost")
    :port                        (Integer/parseInt (or (System/getenv "INFLUXDB_PORT") "8086"))
    ;; unit to use to display rates
    :rate-unit                   TimeUnit/SECONDS
    ;; unit to use to display durations
    :duration-unit               TimeUnit/MILLISECONDS
    ;; prefix to add to all metrics
    :prefix                      "trackit"
    :db-name                     (or (System/getenv "INFLUXDB_DBNAME") "telegraf")
    :auth                        (when (System/getenv "INFLUXDB_USER")
                                   (str (System/getenv "INFLUXDB_USER") ":"
                                        (System/getenv "INFLUXDB_PASS")))
    }))



(defn help
  []
  (println
   "
You can set the following environment variables to change default settings:

  export AWS_ACCESS_KEY_ID='xxx'
  export AWS_SECRET_ACCESS_KEY='yyy'
    - set your API keys when outiside AWS, or use instance profiles instead.

  export AWS_REGION=eu-west-1
    - to set the AWS region to use

  export DYNAMO_TABLE=test-safely-etl-load

  export READ_CAPACITY=10
    - to set the DynamoDB initial read capacity (used only on creation)

  export WRITE_CAPACITY=500
    - to set the DynamoDB initial write capacity (used only on creation)

  export INFLUXDB_HOST=localhost
  export INFLUXDB_PORT=8086
  export INFLUXDB_DBNAME=telegraf
  export INFLUXDB_USER=
  export INFLUXDB_PASS=
    - to send metrics to a InfluxDB

  for a local instance run:
  docker run -d -p 3003:3003 -p 8086:8086 samuelebistoletti/docker-statsd-influxdb-grafana:2.0.0

  then connect to Open http://localhost:3003 (credentials: root/root)
  and add the following two metrics in a graph.

  SELECT last(\"m1_rate\") FROM \"safely.examples.etl_load.save_record.inner\"
     WHERE $timeFilter GROUP BY time($interval) fill(null)

  SELECT last(\"m1_rate\") FROM \"safely.examples.etl_load.save_record.inner_errors\"
     WHERE $timeFilter GROUP BY time($interval) fill(null)

Usage:

  > lein run gen-file  \"/tmp/file.json\" <nrecs>
      To generate a sample data-file. <nrecs> is the number of records
      to generate, default: 1000000 (1M).

  > lein run load-file \"/tmp/file.json\"
      To load the file into dynamodb


Wrong or missing input.
"))



(defn -main
  [& [cmd file nrecs]]
  (init-reporting!)
  (cond

    (and (= cmd "gen-file") (not (empty? file)))
    (gen-records file (or (some-> nrecs (Integer/parseInt)) 1000000) 1000)

    (and (= cmd "load-file") (not (empty? file)))
    (load-records-from-file DEFAUL-CFG file)

    :else
    (do
      (help)
      (System/exit 1))))
