(ns safely.examples.etl-load.core
  (:require
   ;; utilities to create/delete table
   [safely.examples.etl-load.util :as u]
   ;; AWS clojure client
   [amazonica.aws.dynamodbv2 :as dyn]
   ;; JSON parser
   [cheshire.core :as json]
   ;; Clojure parallel reduers
   [clojure.core.reducers :as r]
   ;; utility for working with large files
   [iota]
   ;; Safely retry errors.
   [safely.core :refer [safely]]))


(def DEFAUL-CFG
  {:table-name           (or (System/getenv "DYNAMO_TABLE") "test-safely-etl-load")
   :read-capacity-units  (or (some-> (System/getenv "READ_CAPACITY")  (Integer/parseInt)) 10)
   :write-capacity-units (or (some-> (System/getenv "WRITE_CAPACITY") (Integer/parseInt)) 500)
   :region               (or (System/getenv "AWS_REGION") "eu-west-1")})




(defn save-record
  "takes a single record as a Clojure map and stores it into a dynamo-db
  table. In case of failures it retries until successful completion."
  [{:keys [table-name region] :as cfg} rec]
  (safely

   (dyn/put-item
    {:endpoint region}
    :table-name table-name
    :return-consumed-capacity "TOTAL"
    :return-item-collection-metrics "SIZE"
    :item rec)

   :on-error
   :max-retries :forever
   :log-stacktrace false
   :track-as "safely.examples.etl_load.save_record"))



(defn load-records-from-file
  [{:keys [table-name region] :as cfg} file]
  (println "loading records from file:" file
           "into Dynamo table:" table-name)
  ;; creating table if necessary
  (u/create-table-if-not-exists cfg)
  ;; load files
  (let [recs
        (->> (iota/seq file)                      ; read the file as a seq of lines
             (r/map #(json/parse-string % true))  ; parse each line as JSON
             (r/map (partial save-record cfg))          ; store each record into DynamoDB
             (r/map (constantly 1))               ; count the number of records added
             (r/fold + +))]                       ; reduce and combine counts
    (println "records loaded:" recs)))
