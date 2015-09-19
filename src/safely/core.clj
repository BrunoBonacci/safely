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

(def ^:dynamic *no-sleep* false)


(defun random
  ([:min min :max max]  (+ min (rand-int (- max min))))
  ([base :+/- pct]      (let [variance (int (* base pct))]
                          (rand-between (- base variance) (+ base variance)))))


(defn- exponential-seq
  ([base max-value]
   (map #(min % max-value) (exponential-seq base)))
  ([base]
   (let [base   (Math/abs base)
         log10  (fn [n] (/ (Math/log n) (Math/log 10)))
         pow    (fn [b p] (apply *' (repeat p b)))
         size   (int (log10 base))
         factor (pow 10 size)]
     (map :value
          (iterate (fn [{:keys [base size factor step] :as d}]
                     (let [value (quot (*' factor (pow (/ base factor) (inc step))) 1)]
                       (-> (assoc d :value value)
                           (update :step inc))))
                   {:size size :factor factor :base base :step 1 :value base})))))


(defun sleep
  ([n]              (when-not *no-sleep*
                      (try
                        (Thread/sleep n)
                        (catch Exception x#))))
  ([:min a :max b]  (sleep (random :min a :max b)))
  ([b :+/- v]       (sleep (random b :+/- v))))


(defun sleeper
  ([:fix n]                      (fn [] (sleep n)))
  ([:random b :+/- v]            (fn [] (sleep b :+/- v)))
  ([:random-range :min l :max h] (fn [] (sleep :min l :max h)))

  ([:random-exp-backoff :base b :+/- v]
   (let [sleep-times (atom (cons 0 (exponential-seq b)))]
     (fn []
       (let [[t] (swap! sleep-times rest)]
         (sleep t :+/- v)))))

  ([:random-exp-backoff :base b :+/- v :max m]
   (let [sleep-times (atom (cons 0 (exponential-seq b m)))]
     (fn []
       (let [[t] (swap! sleep-times rest)]
         (sleep t :+/- v))))))


(defn- make-attempt [f]
  (try
    [(f)]
    (catch Throwable x
      (println "ERROR::" (.getMessage x))
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

  )
