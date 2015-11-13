(ns safely.core
  (:require [clojure.core.match :refer [match]]
            [defun :refer [defun]]
            [taoensso.timbre :as log]
            [samsara.trackit :refer [track-rate]]))

;;
;; TODO:
;; * handlers
;; * code doc
;;


(def ^:dynamic *sleepless-mode* false)
(def ^:const defaults
  {:attempt     0
   :default     ::undefined
   :message     "Trapped expected error during safe block execution."
   :log-errors  true
   :log-level   :warn
   :max-retry   0
   :retry-delay [:random-exp-backoff :base 3000 :+/- 0.50]
   :track-as    nil})


(defn- apply-defaults [cfg defaults]
  (merge defaults cfg))



(defun random
  ([:min min :max max]  (+ min (rand-int (- max min))))
  ([base :+/- pct]      (let [variance (int (* base pct))]
                          (random :min (- base variance) :max (+ base variance)))))



(defn- exponential-seq
  ([base max-value]
   (map #(min % max-value) (exponential-seq base)))
  ([^long base]
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



(defn- make-attempt
  [{:keys [message log-errors log-level]}
   f]
  (try
    [(f)]
    (catch Throwable x
      (when log-errors
        (log/log log-level x message))
      [::got-error x])))



(defn safely-fn
  "Safely offers a safe code execution against Exceptions.
   It offers a declarative approach to a large number of handling strategies.
   Usage:

          (safely-fn
             f
             & handling-options)

   The available handling options are:

     :default <value>
        will return <value> if the execution of <f> fails.

     :max-retry <n>
        will retry the code block in case of failures for a maximum
        of <n>. Since this express the 're-tries' you should assume
        the total number of attempts to be at most n + 1.
        Used in conjunction with :default will retry first, and if
        all attempts fails the default value will be returned instead.
        The time between each retry is determined by one of the
        following options, the default strategy is: `:random-exp-backoff'

     :fix <millis> (not recommended)
        To sleep a fix amount of time between retries.

     :random-range :min <millis> :max <millis>
        To sleep a random amount of time between retries within
        certain a :min and :max time.

     :random <millis> :+/- <pct>
        To sleep a random amount of time <millis> each retry which
        is randomized with a +/- <pct> of the base value.
        Eg: `:random 5000 :+/- 0.35` will sleep 5s with +/- 35%

     :random-exp-backoff :base <millis> :+/- <pct> [:max <millis>]
        To sleep a random amount of time which will exponentially
        grow between retries. (see documentation for more info)

     :rand-cycle [<millis1> <millis2> ... <millisN>] :+/- <pct>
        To sleep cycling the given list and randomizing by +/- <pct>.
        On the first retry will wait <millis1> +/- <pct>, on the second
        retry will wait <millis2> +/- <pct> as so on. If the :max-retry
        exceeds the number of waiting time it will restart from <millis1>.


   Exceptions are logged automatically. Here some options to control logging

     :log-errors false
        To disable logging

     :log-level <level> (default :warn)
        To log the errors with a given error level, available options:
        :trace, :debug, :info, :warn, :error, :fatal, :report

     :message \"a custom error message\"
        To log the error with a custom message which helps to contextualize
        the error message.

   It is possible to track the number or and the rate of error automatically
   in your monitoring system of choice by just adding the name under which
   you want to track this error. By default is not enabled.

     :track-as \"myproject.errors.mymodule.myaction\"
        Will use the given string as name for the metric. Use names which
        will be clearly specifying the which part of your code is failing
        for example: \"app.errors.db.writes\"
        and \"app.errors.services.account.fetchuser\" clearly specify
        which action if currently failing. The tracking is done via
        Samsara/TrackIt! (see: https://github.com/samsara/trackit)

  (see website for more documentation: https://github.com/BrunoBonacci/safely)
  "
  [f & {:as spec}]
  (let [spec' (apply-defaults spec defaults)
        ;; lazy execution as only needed in case of error
        delayer (delay (apply sleeper (:retry-delay spec')))]
    (loop [{:keys [message default max-retry attempt track-as] :as data} spec']
      (let [[result ex] (make-attempt spec' f)]
        ;; check execution outcome
        (if (not= ::got-error result)
          ;; it ran successfully
          result
          ;; else: we have an error
          (do
            ;; track the rate/count of errors
            (when (and track-as (= ::got-error result))
              (track-rate track-as))
            ;; handle the outcome
            (cond
              ;; we reached the max retry but we have a default
              (and (not= ::undefined default) (>= attempt max-retry))
              default

              ;; we got error and reached the max retry
              (and (= ::undefined default) (>= attempt max-retry))
              (throw (ex-info message data ex))

              ;; retry
              :else
              (do
                (@delayer)
                (recur (update data :attempt inc))))))))))



(defmacro safely
  "Safely offers a safe code execution against Exceptions.
   It offers a declarative approach to a large number of handling strategies.
   Usage:

          (safely
             & code
             :on-error
             & handling-options)

   The available handling options are:

     :default <value>
        will return <value> if the execution of <code> fails.

     :max-retry <n>
        will retry the code block in case of failures for a maximum
        of <n>. Since this express the 're-tries' you should assume
        the total number of attempts to be at most n + 1.
        Used in conjunction with :default will retry first, and if
        all attempts fails the default value will be returned instead.
        The time between each retry is determined by one of the
        following options, the default strategy is: `:random-exp-backoff'

     :fix <millis> (not recommended)
        To sleep a fix amount of time between retries.

     :random-range :min <millis> :max <millis>
        To sleep a random amount of time between retries within
        certain a :min and :max time.

     :random <millis> :+/- <pct>
        To sleep a random amount of time <millis> each retry which
        is randomized with a +/- <pct> of the base value.
        Eg: `:random 5000 :+/- 0.35` will sleep 5s with +/- 35%

     :random-exp-backoff :base <millis> :+/- <pct> [:max <millis>]
        To sleep a random amount of time which will exponentially
        grow between retries. (see documentation for more info)

     :rand-cycle [<millis1> <millis2> ... <millisN>] :+/- <pct>
        To sleep cycling the given list and randomizing by +/- <pct>.
        On the first retry will wait <millis1> +/- <pct>, on the second
        retry will wait <millis2> +/- <pct> as so on. If the :max-retry
        exceeds the number of waiting time it will restart from <millis1>.


   Exceptions are logged automatically. Here some options to control logging

     :log-errors false
        To disable logging

     :log-level <level> (default :warn)
        To log the errors with a given error level, available options:
        :trace, :debug, :info, :warn, :error, :fatal, :report

     :message \"a custom error message\"
        To log the error with a custom message which helps to contextualize
        the error message.

   It is possible to track the number or and the rate of error automatically
   in your monitoring system of choice by just adding the name under which
   you want to track this error. By default is not enabled.

     :track-as \"myproject.errors.mymodule.myaction\"
        Will use the given string as name for the metric. Use names which
        will be clearly specifying the which part of your code is failing
        for example: \"app.errors.db.writes\"
        and \"app.errors.services.account.fetchuser\" clearly specify
        which action if currently failing. The tracking is done via
        Samsara/TrackIt! (see: https://github.com/samsara/trackit)

  (see website for more documentation: https://github.com/BrunoBonacci/safely)
  "
  [& code]
  (let [[body _ options :as seg] (partition-by #{:on-error} code)]
    (if (not= 3 (count seg))
      (throw (IllegalArgumentException. "Missing or invalid ':on-error' clause."))
      `(safely-fn
        (fn []
          ~@body)
        ~@options))))
