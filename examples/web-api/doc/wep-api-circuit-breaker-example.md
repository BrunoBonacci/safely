# Safely usage for Web APIs.

This example shows how `safely` circuit breaker can be effective in
protecting your system against downstream dependencies failures.


## Scenario

This web API implements a key/value store API as a REST interface.
There are only two methods implemented:

To store a json payload against a key

    >>> PUT /[:key]
    >>> Content-Type: application/json
    >>>
    >>> {"whatever": "you want"}

to retrieve the value of a key

    >>> GET /[:key]

    <<< HTTP/1.1 200 OK
    <<< Content-Type: application/json
    <<<
    <<< {"whatever": "you want"}

For this simple app we will use `ring` and `jetty` with `compojure` for
the request routing.


## Naive implementation.

A simple and naive implementation could look like this:

Requiring necessary namespaces:

``` clojure
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
   [safely.core :refer [safely]])
  (:gen-class))
```

The functions to read/store key/value pairs form downstream db.

``` clojure
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
```

Here our routes definition:

``` clojure
;;
;; the RESTful layer definition
;;
(defroutes app-routes

  ;; stores the key, body is the value
  (PUT "/:key" [key :as {:keys [body]}]

       (db-store @db-config key body)
       {:status 200 :body "OK"})

  ;; retrieves the key if present
  (GET "/:key" [key]

       (if-let [body @(db-load db-config key)]
         {:status 200 :body body}
         {:status 404 :body (format "key '%s' was not found." key)}))

  (route/not-found "Not Found"))
```

Adding some middlewares to handle JSON payloads and the main function.

``` clojure
;; the main app handler
(def app
  (-> app-routes
      (wrap-json-response)
      (wrap-json-body {:keywords? true})))


;; application main
(defn -main []
  (println "Starting server on port 8000")
  (run-jetty app {:port 8000}))
```

At this point we can start our server and issue HTTP GET and PUT
requests, and our web API is done.


## Enter `safely`'s circuit-breaker.

The API as shown above has a major flaw. The availability of the API
is tight to the availability of the downstream db. Obviously if the
downstream database is down this system won't be able to serve
requests. However you won't expect this system to crash if a network
partition occur between the system and the database.  Even in case the
partition is not full, but there is a huge spike on latency between
the system and the database, requests will start piling up and
eventually this application could crash.

To ensure that this system is resilient and tolerant of downstream
failures, we need to add a circuit breaker to protect the system from
downstream failures. Luckily, with `safely` this is extremely easy to
do. Let's revisit the code above.

Previously, our PUT handler looked like this:

``` clojure
  ;; stores the key, body is the value
  (PUT "/:key" [key :as {:keys [body]}]

       (db-store @db-config key body)
       {:status 200 :body "OK"})

```

In order to secure the `db-store` call from downstream failures,
the only change required is to wrap the call into a `safely` block.

``` clojure
  ;; stores the key, body is the value
  (PUT "/:key" [key :as {:keys [body]}]

       (safely                                     ;; wrap call in a safely block
        (db-store @db-config key body)
        :on-error
        :circuit-breaker :db-store                 ;; activate circuit breaker
        :timeout 500                               ;; set a timeout of 500 millis
        :track-as "safely.examples.web-api.store") ;; track stats
       {:status 200 :body "OK"})
```

With use the `:circuit-breaker` option and we give a name to this
operation `:db-store`.  This will be used to identify the circuit
breaker and publish stats. Then we define a `:timeout 500` millis for
this operation. It means that if the operation won't complete withing
the given time the safely block will raise a timeout exception.  It is
always better to set a timeout for the operation so that the caller
will always receive a response withing a given time. Then if the
response is a timeout the caller knows that it can retry the operation
some time later.  Always ensure operations are idempotent, so that
they can be retried without causing duplication.

Now we do the same for the GET operation.

``` clojure
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
```

That's it! Now both operations are safe against downstream partitions.

The final part is to turn the errors that will be raised by the
circuit breaker into the appropriate HTTP status code. The
[RFC6585](https://tools.ietf.org/html/rfc6585) defines the `HTTP 429
Too Many Requests` as the appropriate status code to inform the caller
that it needs to slow down and retry later.

So we can add a wrapper that it will check the raised exceptions, and
if they are originated by `safely` circuit breaker, then we return the
appropriate status code.


``` clojure
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
```

Finally add the middleware to the main `app`.

``` clojure
;; the main app handler
(def app
  (-> app-routes
     (wrap-circuit-breaker)              ;; sends appropriate HTTP 429
     (wrap-json-response)
     (wrap-json-body {:keywords? true})))
```
