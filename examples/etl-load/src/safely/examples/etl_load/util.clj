(ns safely.examples.etl-load.util
  (:require [amazonica.aws.dynamodbv2 :as dyn]
            [safely.core :refer [safely]]))



(defn create-table-if-not-exists
  [{:keys [table-name read-capacity-units write-capacity-units region] :as cfg}]
  (safely

   (when-not
       (safely
        (dyn/describe-table {:endpoint region} :table-name table-name)
        :on-error
        :log-stacktrace false
        :default nil)

     (println "Creating table:" table-name)
     (dyn/create-table
      {:endpoint region}
      :table-name table-name
      :key-schema
      [{:attribute-name "id"   :key-type "HASH"}]
      :attribute-definitions
      [{:attribute-name "id"   :attribute-type "S"}]
      :provisioned-throughput
      {:read-capacity-units  read-capacity-units
       :write-capacity-units write-capacity-units}))

   :on-error
   :max-retry 5
   :message "Creating table"
   :log-stacktrace false
   :track-as "safely.examples.etl_load.create_table"))



(defn delete-table-if-exists
  [{:keys [table-name region] :as cfg}]
  (safely

   (when
       (safely
        (dyn/describe-table {:endpoint region} :table-name table-name)
        :on-error
        :default nil)

     (println "Deleting table:" table-name)
     (dyn/delete-table
      {:endpoint region}
      :table-name table-name))

   :on-error
   :max-retry 5
   :message "Deleting table table"
   :log-stacktrace false))
