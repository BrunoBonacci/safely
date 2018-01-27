(ns safely.examples.etl-load.main
  (:gen-class)
  (:require [safely.examples.etl-load.core :refer [DEFAUL-CFG load-records-from-file]]
            [safely.examples.etl-load.gen-file :refer [gen-records]]))



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
  (cond

    (and (= cmd "gen-file") (not (empty? file)))
    (gen-records file (or (some-> nrecs (Integer/parseInt)) 1000000) 1000)

    (and (= cmd "load-file") (not (empty? file)))
    (load-records-from-file DEFAUL-CFG file)

    :else
    (do
      (help)
      (System/exit 1))))
