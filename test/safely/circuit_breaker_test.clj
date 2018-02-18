(ns safely.circuit-breaker-test
  (:require [midje.sweet :refer [fact just anything]]
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



(fact-with-test-pools
 "The circuit breaker should go form `:closed` to `:open` and
 from `:open` to `:half-open` and finally from `:half-open`
 to `:closed` when things get back to normal."

 (let [ok   (fn [] :ok)
       slow (fn [x] (sleep 100) x)

       f (crash-boom-bang!
          ;; :closed -> :open
          #(boom) #(boom) #(boom)

          ;; :open -> :half-open
          #(ok) #(ok) #(ok)

          ;; :half-open -> :closed
          #(ok) #(ok) #(ok) #(ok) #(ok)

          ;; :closed
          #(ok) #(ok) #(ok)
          )]

   (->>
    (for [i (range 14)]

      (slow
       [(:status (circuit-breaker-info :test))
        (simple-result
         (safely

          (f)

          :on-error
          :log-stacktrace    false
          :circuit-breaker   :test
          :grace-period      300
          :ramp-up-period    500
          ))]))
    doall
    ))
 => (just [[nil :boom]
           [:closed :boom]
           [:closed :boom]
           [:open :circuit-open]
           [:open :circuit-open]
           [:open :circuit-open]
           (just [:half-open anything])
           (just [:half-open anything])
           (just [:half-open anything])
           (just [:half-open anything])
           (just [:half-open anything])
           [:closed :ok]
           [:closed :ok]
           [:closed :ok]
           ]))



(fact-with-test-pools
 "The circuit breaker should go form `:closed` to `:open` and
 from `:open` to `:half-open` and finally from `:half-open`
 back to `:open` when requests keep failing"

 (let [ok   (fn [] :ok)
       slow (fn [x] (sleep 100) x)

       f (crash-boom-bang!
          ;; :closed -> :open
          #(boom) #(boom) #(boom)

          ;; :open -> :half-open
          #(boom) #(boom) #(boom)

          ;; :half-open -> :open
          #(boom) #(boom) #(boom)

          ;; :open
          #(boom) #(boom) #(boom)
          )]

   (->>
    (for [i (range 12)]

      (slow
       [(:status (circuit-breaker-info :test))
        (simple-result
         (safely

          (f)

          :on-error
          :log-stacktrace    false
          :circuit-breaker   :test
          :grace-period      300
          :ramp-up-period    300
          ))]))
    doall
    last
    ))
 => [:open :circuit-open])



(fact-with-test-pools
 "When the timeout time elapses, we can still leave the task in the
 background."

 (safely
  :ok
  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5)

 (sleep 100)

 (-> (circuit-breaker-info :test) :in-flight) => 0

 (safely
  (sleep 5000)
  :ok

  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5
  :timeout           1000
  :cancel-on-timeout :never
  :default nil) => nil

 (-> (circuit-breaker-info :test) :in-flight) => 1
 )



(fact-with-test-pools
 "When the timeout time elapses, we can cancel the request."

 (safely
  :ok
  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5)

 (sleep 100)

 (-> (circuit-breaker-info :test) :in-flight) => 0

 (safely
  (sleep 5000)
  :ok

  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5
  :timeout           1000
  :cancel-on-timeout :always
  :default nil) => nil

 (-> (circuit-breaker-info :test) :in-flight) => 0
 )



(fact-with-test-pools
 "When the timeout time elapses, we can cancel the request if it is
 still in the queue. (:cancel-on-timeout :if-not-running)"

 (with-parallel 10 ;; thread-pool-size: 10 + queue size: 5
   (safely
    (sleep 2000)
    :ok

    :on-error
    :circuit-breaker :test
    :thread-pool-size  10
    :queue-size        5))

 (sleep 100)

 (-> (circuit-breaker-info :test) :in-flight) => 10

 (safely
  (sleep 2000)
  :ok

  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5
  :timeout           1000
  :cancel-on-timeout :if-not-running
  :default nil) => nil

 ;; cancelled tasks which are in the queue are purged
 ;; only at the time they are scheduled
 (-> (circuit-breaker-info :test) :in-flight) => 11
 (sleep 2500)
 (-> (circuit-breaker-info :test) :in-flight) => 0
 )


(fact-with-test-pools
 "When the timeout time elapses, we can cancel the request if it is
 still in the queue. (:cancel-on-timeout :always)"

 (with-parallel 10 ;; thread-pool-size: 10 + queue size: 5
   (safely
    (sleep 2000)
    :ok

    :on-error
    :circuit-breaker :test
    :thread-pool-size  10
    :queue-size        5))

 (sleep 100)

 (-> (circuit-breaker-info :test) :in-flight) => 10

 (safely
  (sleep 2000)
  :ok

  :on-error
  :log-stacktrace    false
  :circuit-breaker   :test
  :thread-pool-size  10
  :queue-size        5
  :timeout           1000
  :cancel-on-timeout :always
  :default nil) => nil

 ;; cancelled tasks which are in the queue are purged
 ;; only at the time they are scheduled
 (-> (circuit-breaker-info :test) :in-flight) => 11
 (sleep 2500)
 (-> (circuit-breaker-info :test) :in-flight) => 0
 )
