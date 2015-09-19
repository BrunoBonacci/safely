(ns safely.core
  (:require [clojure.core.match :refer [match]]
            [defun :refer [defun]]))

;;
;; * exception protection
;; * retry
;; * backoff
;; * randomisation
;; * messaging
;; * handlers
;;


(defun random
  ([:min min :max max]  (+ min (rand-int (- max min))))
  ([base :+/- pct]      (let [variance (int (* base pct))]
                          (rand-between (- base variance) (+ base variance)))))


(defun sleep
  ([n]              (try
                      (Thread/sleep n)
                      (catch Exception x#)))
  ([:min a :max b]  (sleep (random :min a :max b)))
  ([b :+/- v]       (sleep (random b :+/- v))))



(defn- make-attempt [f]
  (try
    [(f)]
    (catch Throwable x
      (println x)
      [::got-error x])))


(defn safely-fn
  [f & {:as spec}]
  (let [defaults {:max-retry 0 :attempt 0 :retry-delay 1000}]
    (loop [{:keys [message max-retry retry-delay attempt]
            :as data} (merge defaults spec)]
      (let [[result ex] (make-attempt f)]
        (cond
          ;; it ran successfully
          (not= ::got-error result)
          result

          ;; we got error and reached the max retry
          (>= attempt max-retry)
          (throw (ex-info message data ex))

          ;; retry
          :else
          (do
            (sleep retry-delay)
            (println data)
            (recur (update data :attempt inc))))))))


(comment
  (safely-fn (fn [] (/ 1 0) (println "done")) :max-retry 3)

  (def defaults {:max-retry 0 :attempt 0 :retry-delay 1000})
  (merge defaults {:max-retry 3})

  )
