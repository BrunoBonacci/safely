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


(interleave [:a :c :d] [:b])

(expect {:result true}
        (in (tc/quick-check 1000 rand-between-boudaries)))


;;
;; Successful execution
;;
(expect 5
        (safely (/ 10 2)
         :on-error
         :default 1))


;;
;; using :default value
;;
(expect 1
        (safely (/ 1 0)
         :on-error
         :default 1))


;;
;; using :max-retry to retry at most
;;
(expect 4
        (count-retry
         (safely
          (boom)
          :on-error
          :max-retry 3
          :default 1)
         ))



;;
;; using :default as exit clause after :max-retry to retry at most
;;
(expect 10
        (sleepless
         (safely
          (boom)
          :on-error
          :max-retry 3
          :default 10)))
