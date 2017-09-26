(ns safely.core-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [expectations :refer :all]
            [safely.core :refer :all]))


(def ^:dynamic *counter* nil)

(defn count-passes []
  (swap! *counter* inc))


(defn boom []
  (throw (ex-info "booom" {})))


(defmacro count-retry [body]
  (let [body# `(~(first body) (count-passes) ~@(next body))]
    `(binding [*counter* (atom 0)
               *sleepless-mode* true]
       ~body#
       @*counter*)))


(defmacro sleepless [& body]
  `(binding [*sleepless-mode* true]
     ~@body))


(def rand-between-boudaries
  (prop/for-all [a gen/int
                 b gen/int]
                (let [m1 (min a b)
                      m2 (max a b)]
                  (<= m1 (random :min a :max b) m2))))



(expect {:result true}
        (in (tc/quick-check 1000 rand-between-boudaries)))


;;
;; Successful execution
;;
(expect 5
        (safely (/ 10 2)
         :on-error
         :log-stacktrace false
         :default 1))


;;
;; using :default value
;;
(expect 1
        (safely (/ 1 0)
         :on-error
         :log-stacktrace false
         :default 1))


;;
;; using :max-retry to retry at most
;;
(expect 4
        (count-retry
         (safely
          (boom)
          :on-error
          :log-stacktrace false
          :max-retry 3
          :default 1)))


;;
;; using :max-retry to retry at most
;; if recover from failure value should be returned
;;
(expect 2
        (count-retry
         (safely
          (if (>= @*counter* 2)
            10
            (throw (RuntimeException. "boom")))
          :on-error
          :log-stacktrace false
          :max-retry 3
          :default 1)))



;;
;; using :default as exit clause after :max-retry to retry at most
;;
(expect 10
        (sleepless
         (safely
          (boom)
          :on-error
          :log-stacktrace false
          :max-retry 3
          :default 10)))



;;
;; using :max-retry without :default raises an exception
;;
(expect clojure.lang.ExceptionInfo
        (sleepless
         (safely
          (boom)
          :on-error
          :log-stacktrace false
          :max-retry 3)))


;;
;; using `:retryable-error?` predicate to filter which error should be retried
;;
(expect ArithmeticException
        (sleepless
         (safely
          (/ 1 0)
          :on-error
          :log-stacktrace false
          :max-retry 5
          :default 10
          :retryable-error? #(not (#{ArithmeticException} (type %))))))


(expect 10  ;; in this case
        (sleepless
         (safely
          (boom)
          :on-error
          :log-stacktrace false
          :max-retry 5
          :default 10
          :retryable-error? #(not (#{ArithmeticException} (type %))))))
