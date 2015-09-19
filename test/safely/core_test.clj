(ns safely.core-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [safely.core :refer :all]))


(def rand-between-boudaries
  (prop/for-all [a gen/int
                 b gen/int]
                (let [m1 (min a b)
                      m2 (max a b)]
                  (<= m1 (random :min m1 :max m2) m2))))


(tc/quick-check 1000 rand-between-boudaries)
