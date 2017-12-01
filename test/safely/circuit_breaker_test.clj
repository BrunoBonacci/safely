(ns safely.circuit-breaker-test
  (:require [safely.circuit-breaker :refer :all]
            [midje.sweet :refer :all]
            [safely.test-utils :refer :all]
            [safely.core :refer :all]))


#_(safely.core/safely
   (println "long running job")
   (Thread/sleep (rand-int 3000))
   (if (< (rand) 1/3)
     (throw (ex-info "boom" {}))
     (rand-int 1000))
   :on-error
   :circuit-breaker :safely.test
   :thread-pool-size 10
   :queue-size       5
   :sample-size      100
   :timeout          2000
   :counters-buckets 10
   :circuit-closed?  closed?-by-failure-threshold
   :failure-threshold 0.5)


;;
;; initialising circuit breakers used in the tests
;; to make sure that the configuration is not
;; assumed to be different on every test.
;;
(safely
 "Circuit :test1"
 :on-error
 :circuit-breaker  :test1
 :thread-pool-size  10
 :queue-size        5
 :sample-size       100
 :timeout           2000
 :counters-buckets  10
 :circuit-closed?   #'closed?-by-failure-threshold
 :failure-threshold 0.5)


(safely
 "Circuit :test-open1"
 :on-error
 :circuit-breaker  :test-open1
 :thread-pool-size  10
 :queue-size        5
 :sample-size       100
 :timeout           2000
 :counters-buckets  10
 :circuit-closed?   #'closed?-by-failure-threshold
 :failure-threshold 0.5)


(fact

 "A successful sequence of call must be successful when using a circuit breaker"

 (->> (range 1001)
      (map
       (fn [i]
         (safely
          i
          :on-error
          :circuit-breaker :test1)))
      (reduce +)) => 500500
 )


(fact
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit."

 (->>
  (with-parallel 10 ;; thread-pool-size: 10
    (safely
     (sleep 100)
     :ok

     :on-error
     :circuit-breaker :test1
     :thread-pool-size  10))
  frequencies) => {:ok 10}
 )



(fact
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit including queue."

 (->>
  (with-parallel 15  ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (sleep 100)
     :ok

     :on-error
     :circuit-breaker   :test1
     :thread-pool-size  10
     :queue-size        5))

  frequencies) => {:ok 15})




(fact
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit including queue.
 All concurrent requests beyond the size of the circuit should fail
 with a `queue-full` error."

 (->>
  (with-parallel 20  ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (sleep 100)
     :ok

     :on-error
     :log-stacktrace    false
     :circuit-breaker   :test1
     :thread-pool-size  10
     :queue-size        5))

  frequencies) => {:ok 15 :queue-full 5})



(fact
 "If requests are failing and the circuit is tripped open,
  then subsequent requests will be rejected immediately."

 (->>
  (with-parallel 10 ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (boom)

     :on-error
     :log-stacktrace    false
     :circuit-breaker   :test-open1))

  frequencies) => {:boom 10}

 ;; wait for the previous batch to complete
 (sleep 1000)

 (->>
  (with-parallel 5
    (safely
     (sleep 100)
     :ok

     :on-error
     :log-stacktrace    false
     :circuit-breaker   :test-open1))

  frequencies) => {:circuit-open 5}

 )
