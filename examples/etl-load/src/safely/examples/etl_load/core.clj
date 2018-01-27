(ns safely.examples.etl-load.core
  (:require [safely.examples.etl-load.util :as u]
            [amazonica.aws.dynamodbv2 :as dyn]
            [cheshire.core :as json]
            [clojure.core.reducers :as r]
            [iota]
            [safely.core :refer [safely]]))


(def DEFAUL-CFG
  {:table-name           (or (System/getenv "DYNAMO_TABLE") "test-safely-etl-load")
   :read-capacity-units  (or (some-> (System/getenv "READ_CAPACITY")  (Integer/parseInt)) 10)
   :write-capacity-units (or (some-> (System/getenv "WRITE_CAPACITY") (Integer/parseInt)) 500)
   :region               (or (System/getenv "AWS_REGION") "eu-west-1")})




(defn save-record
  [{:keys [table-name region] :as cfg} rec]
  (safely

   (dyn/put-item
    {:endpoint region}
    :table-name table-name
    :return-consumed-capacity "TOTAL"
    :return-item-collection-metrics "SIZE"
    :item rec)

   :on-error
   :max-retry :forever))



(defn load-records-from-file
  [{:keys [table-name region] :as cfg} file]
  (println "loading records from file:" file
           "into Dynamo table:" table-name)
  ;; creating table if necessary
  (u/create-table-if-not-exists cfg)
  ;; load files
  (let [recs
        (->> (iota/seq file)
             (r/map #(json/parse-string % true))
             (r/map (partial save-record cfg))
             (r/map (constantly 1))
             (r/fold + +))]
    (println "records loaded:" recs)))
