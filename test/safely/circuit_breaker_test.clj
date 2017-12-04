(ns safely.circuit-breaker-test
  (:require [safely.circuit-breaker :refer :all]
            [safely.core :refer :all]
            [safely.test-utils :refer :all]))


(fact-with-test-pools

 "A successful sequence of call must be successful when using a circuit breaker"

 (->> (range 1001)
      (map
       (fn [i]
         (safely
          i
          :on-error
          :circuit-breaker :test)))
      (reduce +)) => 500500
 )




(fact-with-test-pools
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit."

 (->>
  (with-parallel 10 ;; thread-pool-size: 10
    (safely
     (sleep 100)
     :ok

     :on-error
     :circuit-breaker :test
     :thread-pool-size  10))
  frequencies) => {:ok 10}
 )




(fact-with-test-pools
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit including queue."

 (->>
  (with-parallel 15  ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (sleep 100)
     :ok

     :on-error
     :circuit-breaker   :test
     :thread-pool-size  10
     :queue-size        5))

  frequencies) => {:ok 15})




(fact-with-test-pools
 "A parallel successful request must be successful when the number of
 concurrent requests are within the size of the circuit including queue.
 All concurrent requests beyond the size of the circuit should fail
 with a `queue-full` error."

 (->>
  (with-parallel 16  ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (sleep 100)
     :ok

     :on-error
     :log-stacktrace    false
     :circuit-breaker   :test
     :thread-pool-size  10
     :queue-size        5))

  frequencies) => {:ok 15 :queue-full 1})



(fact-with-test-pools
 "If requests are failing and the circuit is tripped open,
  then subsequent requests will be rejected immediately."

 (->>
  (with-parallel 10 ;; thread-pool-size: 10 + queue-size: 5
    (safely
     (sleep 100)
     (boom)

     :on-error
     :log-stacktrace    false
     :circuit-breaker   :test))

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
     :circuit-breaker   :test))

  frequencies) => {:circuit-open 5}

 )
