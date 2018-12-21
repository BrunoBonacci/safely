(ns safely.examples.web-api.core
  (:require
   ;; compojure for request routing
   [compojure.core :refer :all]
   [compojure.route :as route]
   ;; ring with jetty and useful middlewares
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.adapter.jetty :refer [run-jetty]]
   ;; safely
   [safely.core :refer [safely]]
   ;; reporting metrics
   [samsara.trackit :refer [start-reporting!]])
  (:gen-class))


;; database configuration
(def db-config (atom {}))

;; a function to store a value in the external db
(defn db-store [cfg key value]
  ;; serialize and store to favourite db
  )

;; a function to retrieve the current value of
;; a given key. `nil` if the key isn't found.
(defn db-load  [cfg key]
  ;; lookup key in db and deserialize value
  )


;;
;; the RESTful layer definition
;;
(defroutes app-routes

  ;; stores the key, body is the value
  (PUT "/:key" [key :as {:keys [body]}]

       (safely
        (db-store @db-config key body)
        :on-error
        :circuit-breaker :db-store
        :timeout 500
        :track-as "safely.examples.web-api.store")
       {:status 200 :body "OK"})

  ;; retrieves the key if present
  (GET "/:key" [key]

       (if-let [body (safely
                      (db-load @db-config key)
                      :on-error
                      :circuit-breaker :db-load
                      :timeout 300
                      :track-as "safely.examples.web-api.load")]
         {:status 200 :body body}
         {:status 404 :body (format "key '%s' was not found." key)}))

  (route/not-found "Not Found"))


;;
;; This simple wrappers informs the caller to slow down as the
;; downstream system can't keep up with load.  Basically, it check if
;; the exception raised by the `handler` was triggered by the circuit
;; breaker (like for circuit open, queue-full etc) and returns a the
;; appropriate `HTTP 429 Too Many Requests` back to the caller.
;;
(defn wrap-circuit-breaker [handler]
  (fn [r]
    (try
      (handler r)
      (catch Exception x
        (if (= :safely.core/circuit-breaker
               (:origin (ex-data (.getCause x))))
          {:status 429 :body "Please try later"}
          (throw x))))))



;; the main app handler
(def app
  (-> app-routes
     (wrap-circuit-breaker)              ;; sends appropriate HTTP 429
     (wrap-json-response)
     (wrap-json-body {:keywords? true})))


;; application main
(defn -main []
  (println "Reporting metrics to Influxdb on localhost:8086")
  (start-reporting!
   {:type                        :influxdb
    :reporting-frequency-seconds 10
    :host                        "localhost"
    :port                        8086
    :prefix                      "trackit"
    :db-name                     "metrics"
    :auth                        "user1:pass1"})
  (println "Starting server on port 8000")
  (run-jetty app {:port 8000}))
