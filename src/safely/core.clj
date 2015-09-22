(ns safely.core
  (:require [clojure.core.match :refer [match]]
            [defun :refer [defun]]))

;;
;; * messaging
;; * handlers
;;

(def ^:dynamic *sleepless-mode* false)
(def defaults
  {:attempt 0
   :default ::undefined
   :max-retry 0
   :retry-delay [:random-exp-backoff :base 3000 :+/- 0.50]})


(defn- apply-defaults [cfg defaults]
  (merge defaults cfg))


(defun random
  ([:min min :max max]  (+ min (rand-int (- max min))))
  ([base :+/- pct]      (let [variance (int (* base pct))]
                          (random :min (- base variance) :max (+ base variance)))))



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
  ([n]
   (when-not *sleepless-mode*
     (try
       (Thread/sleep n)
       (catch Exception x#))))

  ([:min a :max b]
   (sleep (random :min a :max b)))

  ([b :+/- v]
   (sleep (random b :+/- v))))



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
         (sleep t :+/- v)))))

  ([:rand-cycle c :+/- v ]
   (let [sleep-times (atom (cons 0 (cycle c)))]
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
  (let [spec' (apply-defaults spec defaults)
        delayer (apply sleeper (:retry-delay spec'))]
    (loop [{:keys [message default max-retry attempt] :as data} spec']
      (let [[result ex] (make-attempt f)]
        (cond
          ;; it ran successfully
          (not= ::got-error result)
          result

          ;; we reached the max retry but we have a default
          (and (not= ::undefined default) (>= attempt max-retry))
          default

          ;; we got error and reached the max retry
          (and (= ::undefined default) (>= attempt max-retry))
          (throw (ex-info message data ex))

          ;; retry
          :else
          (do
            (delayer)
            (recur (update data :attempt inc))))))))



(defmacro safely [& code]
  (let [[body _ options] (partition-by #{:on-error} code)]
    `(safely-fn
      (fn []
        ~@body)
      ~@options)))



(comment

  (safely-fn
   (fn []
     (println "executing")
     (/ 1 0))
   :default 1)

  (safely
   ;; ArithmeticException Divide by zero
   (/ 1 0)
   :on-error
   :default 1)
  )
