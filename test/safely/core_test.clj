(ns safely.core-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [midje.sweet :refer :all]
            [safely.core :refer :all]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                     ---==| T E S T   U T I L S |==----                     ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *counter* nil)



(defn count-passes []
  (swap! *counter* inc))



(defn boom []
  (throw (ex-info "BOOOOM!" {:cause :boom})))



(defmacro count-retry [body]
  (let [body# `(~(first body) (count-passes) ~@(next body))]
    `(binding [*counter* (atom 0)
               *sleepless-mode* true]
       [~body#
        @*counter*])))



(defmacro sleepless [& body]
  `(binding [*sleepless-mode* true]
     ~@body))



(defn crash-boom-bang!
  "a utility function which calls the first function in fs
  the first time is called, it calls the second function
  the second time is called and so on. It throws an Exception
  if no more functions are available to fs in a given call."
  [& fs]

  (let [xfs (atom fs)]
    (fn [& args]
      (let [f (or (first @xfs) (fn [&x] (throw (ex-info "No more functions available to call" {:cause :no-more-fn}))))
            _ (swap! xfs next)]
        (apply f args)))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;               ---==| T E S T   R A N D O M I Z E R S |==----               ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def rand-between-boudaries
  (prop/for-all [a gen/int
                 b gen/int]
                (let [m1 (min a b)
                      m2 (max a b)]
                  (<= m1 (random :min a :max b) m2))))



(fact
 "expect randomizer to return a number which is always within the
  given range."

 (tc/quick-check 1000 rand-between-boudaries) => (contains {:result true}))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                    ---==| R E T R Y   L O G I C |==----                    ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(fact
 "A successful execution should return the value of the safe block"

 (safely
  (/ 10 2)
  :on-error
  :log-stacktrace false) => 5
 )



(fact
 "A successful execution should return the value of the safe block
  even if a default is provided."

 (safely
  (/ 10 2)
  :on-error
  :log-stacktrace false
  :default 1) => 5
 )



(fact
 "If exception is thrown and a `:default` value is provided then the
 `:default` value must be returned."

 (safely
  (/ 1 0)
  :on-error
  :log-stacktrace false
  :default 1) => 1

 )



(fact
 "If exception is thrown and a `:default` value is provided then the
 `:default` value must be returned even if the default value is `nil`."

 (safely
  (/ 1 0)
  :on-error
  :log-stacktrace false
  :default nil) => nil

 )


(facts
 "using :max-retry to retry at most n times"


 (fact
  "using :max-retry to retry at most n times - max-retry reached with default value"

  (count-retry
   (safely
    (boom)
    :on-error
    :log-stacktrace false
    :max-retry 3
    :default 1)) => [1 4]
  )



 (fact
  "using :max-retry to retry at most n times - if recover from failure value should be returned"

  (let [expr (crash-boom-bang!
              #(boom)
              (constantly 10))]
    (count-retry
     (safely
      (expr)
      :on-error
      :log-stacktrace false
      :max-retry 3
      :default 1))) => [10 2]
  ))




(fact
 "using :max-retry without :default raises an exception"

 (sleepless
  (safely
   (boom)
   :on-error
   :log-stacktrace false
   :message "my explosion"
   :max-retry 3)) => (throws Exception "my explosion")

 )




(fact
 "using `:retryable-error?` predicate to filter which error should be retried
  - not retryable error case"

 (sleepless
  (safely
   (/ 1 0)
   :on-error
   :log-stacktrace false
   :max-retry 5
   :default 10
   :retryable-error? #(not (#{ArithmeticException} (type %)))))
 => (throws ArithmeticException)

 )




(fact
 "using `:retryable-error?` predicate to filter which error should be retried
  - retryable error case"

 (sleepless
  (safely
   (boom)
   :on-error
   :log-stacktrace false
   :max-retry 5
   :default 10
   :retryable-error? #(not (#{ArithmeticException} (type %)))))
 => 10

 )
