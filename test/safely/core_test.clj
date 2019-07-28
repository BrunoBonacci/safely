(ns safely.core-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [midje.sweet :refer :all]
            [safely.core :refer :all]
            [safely.test-utils :refer :all]))





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
 "using :max-retries to retry at most n times"


 (fact
  "using :max-retries to retry at most n times - max-retries reached with default value"

  (count-attempts
   (safely
    (boom)
    :on-error
    :log-stacktrace false
    :max-retries 3
    :default 1)) => [1 4]
  )



 (fact
  "using :max-retries to retry at most n times - if recover from failure value should be returned"

  (let [expr (crash-boom-bang!
              #(boom)
              (constantly 10))]
    (count-attempts
     (safely
      (expr)
      :on-error
      :log-stacktrace false
      :max-retries 3
      :default 1))) => [10 2]
  ))




(fact
 "using :max-retries without :default raises an exception"

 (sleepless
  (safely
   (boom)
   :on-error
   :log-stacktrace false
   :message "my explosion"
   :max-retries 3)) => (throws Exception "my explosion")

 )



(fact
 ":failed? predicate can be used to evaluate the response of a call
  and determine if the call was successful or not. Unsuccessful calls
  behave like Exceptions."


 (fact
  "using :failed? with :max-retries to retry at most n times - max-retries reached with default value"

  (count-attempts
   (safely
    (- (inc (rand-int 10)))
    :on-error
    :failed? neg?
    :log-stacktrace false
    :max-retries 3
    :default 1)) => [1 4]
  )



 (fact
  "using :failed? with :max-retries to retry at most n times - if recover from failure value should be returned"

  (let [expr (crash-boom-bang!
              (constantly -10)
              (constantly 10))]
    (count-attempts
     (safely
      (expr)
      :on-error
      :failed? neg?
      :log-stacktrace false
      :max-retries 3
      :default 1))) => [10 2]
  )



 (fact
  "using :failed? with :max-retries without :default raises an exception"

  (sleepless
   (safely
    -10
    :on-error
    :failed? neg?
    :log-stacktrace false
    :message "my explosion"
    :max-retries 3)) => (throws Exception "my explosion")

  )



 (fact
  "using :failed? with :max-retries protects from both Exception and failed requests"

  (let [expr (crash-boom-bang!
              #(boom)
              (constantly -2)
              (constantly -2)
              (constantly 10))]
    (count-attempts
     (safely
      (expr)
      :on-error
      :failed? neg?
      :log-stacktrace false
      :max-retries 3
      :default 1))) => [10 4]
  )
 )



(fact
 "using `:retryable-error?` predicate to filter which error should be retried
  - not retryable error case"

 (sleepless
  (safely
   (/ 1 0)
   :on-error
   :log-stacktrace false
   :max-retries 5
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
   :max-retries 5
   :default 10
   :retryable-error? #(not (#{ArithmeticException} (type %)))))
 => 10

 )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                    ----==| D E P R E C A T E D |==----                     ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(facts
 ":max-retry is deprecated but should have the same behaviour of the new :max-retries"

 (facts
  "using :max-retry to retry at most n times"


  (fact
   "using :max-retry to retry at most n times - max-retries reached with default value"

   (count-attempts
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
     (count-attempts
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
 )
