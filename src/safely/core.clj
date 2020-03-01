(ns safely.core
  (:require [clojure.tools.logging :as log]
            [defun.core :refer [defun]]
            [safely.circuit-breaker :refer [execute-with-circuit-breaker]]
            [samsara.trackit :refer [track-rate track-time]]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                      ----==| D E A F U L T S |==----                       ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def ^:dynamic *sleepless-mode* false)
(def ^{:const true :no-doc true} defaults
  {:attempt           0
   :default           ::undefined
   :message           "Trapped expected error during safe block execution."
   :log-errors        true
   :log-level         :warn
   :log-stacktrace    true
   :log-ns            "safely.log"
   :max-retries       0
   :retry-delay       [:random-exp-backoff :base 300 :+/- 0.50 :max 60000]
   :retryable-error?  nil
   :failed?           nil
   :track-as          nil

   ;; Circuit-Breaker options
   ;;:circuit-breaker :name
   :thread-pool-size  10
   :queue-size        5
   :sample-size       100
   :timeout           Long/MAX_VALUE
   :cancel-on-timeout :always ;; :never :if-not-running :always
   :counters-buckets  10

   :circuit-breaker-strategy :failure-threshold
   :failure-threshold 0.5

   :grace-period      3000

   :half-open-strategy :linear-ramp-up
   :ramp-up-period    5000

   })



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ----==| U T I L I T Y   F U N C T I O N S |==----              ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rename-key
  [m old new]
  (if (not= old new)
    (cond-> m
      (not= ::not-found (get m old ::not-found))
      (as-> $ (assoc $ new (get $ old)) (dissoc $ old)))
    m))



(defn- conform-deprecations
  "Replaces the deprecated options with their substitutions"
  [opts]
  (-> opts
     (rename-key :max-retry :max-retries)))



(defn- apply-defaults [cfg defaults]
  (as-> cfg $
    (conform-deprecations $)
    (merge defaults $)
    ;; if failed? is provided user failed? otherwise is false
    (update $ :failed? (fn [p?] (or p? (constantly false))))
    ;; if :max-retries is :forever, then retry as many times as you can
    (update $ :max-retries (fn [mr] (if (= mr :forever) Long/MAX_VALUE mr))) ))



(defmacro outer-tracker
  "utility macro to track execution time a tracker name is provided"
  {:style/indent 1 :no-doc true}
  [name & body]
  `(let [name# ~name]
     (if name#
       (try (track-time (str name# ".outer") ~@body)
            (catch Throwable x#
              (track-rate (str name# ".outer_errors"))
              (throw x#)))
       (do ~@body))))



(defmacro inner-tracker
  "utility macro to track execution time a tracker name is provided"
  {:style/indent 1 :no-doc true}
  [name & body]
  `(let [name# ~name]
     (if name#
       (try (track-time (str name# ".inner") ~@body)
            (catch Throwable x#
              (track-rate (str name# ".inner_errors"))
              (throw x#)))
       (do ~@body))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                      ----==| S L E E P E R S |==----                       ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defun random
  "Returns a uniformly distributed random value within certain boundaries.

   It can be used as following:

   - `(random :min 100 :max 300)` It returns a random integer between `100` and
     `300` (excluded)

   - `(random 300 :+/- 0.50)` It returns a random integer uniformly distributed
     between `150` and `450` (excluded)

  "
  ([:min min :max max]  (+ min (rand-int (- max min))))
  ([base :+/- pct]      (let [variance (int (* base pct))]
                          (random :min (- base variance) :max (+ base variance)))))



(defn- exponential-seq
  "Produces a sequence of exponentially bigger wait times.
   The exponential base is taken from the most significant
   digit of `base` and not the entire number.
   For more info see: https://github.com/BrunoBonacci/safely
  "
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
  "It sleeps for at least `n` millis when not interrupted.
   If interrupted, it doesn't throw an exception.

   It can be called in the following ways

   - `(sleep n)`
     It sleeps at least `n` milliseconds, if not interrupted *(NOT RECOMMENDED)*

   - `(sleep b :+/- v)`
     It sleeps random `b` millis `+/-` `v`%, for example:
     `(sleep 3000 :+/- 0.5)` means that it will sleep for
     `3s +/- 50%`, therefore the actual interval can be between
     `1500` millis and `4500` millis (random uses uniform distribution)

   - `(sleep :min l :max h)`
     It sleeps for a random amount of time between `min` and `:max`

  "
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
  "It returns a function (potentially stateful) which will sleep for a
   given amount of time each time it is called. All sleeps are
   randomized with the exception of the `[:fix n]` sleep (not
   recommended).

   It can be called in the following ways

   - `[:fix n]`
     It sleeps at least `n` milliseconds *(NOT RECOMMENDED)*

   - `[:random b :+/- v]`
     It sleeps random `b` millis `+/-` `v`%, for example:
     `[:random 3000 :+/- 0.5]` means that it will sleep for
     `3s +/- 50%`, therefore the actual interval can be between
     `1500` millis and `4500` millis (random uses uniform distribution)

   - `[:random-range :min l :max h]`
     It sleeps for a random amount of time between `min` and `:max`

  "

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



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                  ----==| A T T E M P T   C A L L |==----                   ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn- make-attempt-direct
  [{:keys [message log-ns log-errors log-level log-stacktrace call-site]}
   f]
  (try
    [(f)]
    (catch Throwable x
      (when log-errors
        (log/log log-ns log-level (when log-stacktrace x)
                 (str message " @ " call-site ", reason: " (.getMessage ^Throwable x))))
      [nil x])))



(defn- track-error-type
  [{:keys [circuit-breaker track-as] :as cb-opts} [value failure error :as outcome]]
  (if-not track-as
    outcome
    (let [track-prefix (str track-as ".circuit_breaker." (name circuit-breaker))]
      (if (nil? failure)
        ;; successful execution
        (track-rate (str track-prefix ".success"))

        ;; track all errors
        (track-rate (str track-prefix ".errors.all")))

      (cond
        ;; exception thrown
        (= :error failure)        (track-rate (str track-prefix ".errors.execution"))

        ;; queue-full
        (= :queue-full failure)   (track-rate (str track-prefix ".errors.queue_full"))

        ;; timeout
        (= :timeout failure)      (track-rate (str track-prefix ".errors.timeout"))

        ;; circuit-open
        (= :circuit-open failure) (track-rate (str track-prefix ".errors.circuit_open")))
      outcome)))



(defn- exception
  "Returns an exception originated from the circuit breaker and with
  the :cause correctly populated."
  [circuit-breaker msg cause & {:as data}]
  (ex-info msg (assoc data :cause cause
                      :origin ::circuit-breaker
                      :circuit-breaker circuit-breaker)))



(defn- normalize-failure
  [{:keys [circuit-breaker] :as opts} [value failure error]]
  (cond
    ;; successful execution
    (nil? failure)            [value]

    ;; exception thrown
    (= :error failure)        [nil error]

    ;; queue-full
    (= :queue-full failure)   [nil (exception circuit-breaker "The circuit breaker queue is full" :queue-full)]

    ;; timeout
    (= :timeout failure)      [nil (exception circuit-breaker "timeout" :timeout)]

    ;; circuit-open
    (= :circuit-open failure) [nil (exception circuit-breaker "circuit-open" :circuit-open)]))




(defn- make-attempt-with-circuit-breaker
  [{:keys [message log-ns log-errors log-level log-stacktrace call-site] :as opts} f]
  (let [[value error :as result] (->>
                                  (execute-with-circuit-breaker f opts)
                                  (track-error-type opts)
                                  (normalize-failure opts))]
    ;; log error if required
    (when (and error log-errors)
      (log/log log-ns log-level (when log-stacktrace error)
               (str message " @ " call-site ", reason: " (.getMessage ^Throwable error))))
    ;; return operation result
    result))



(defn- make-attempt
  [{:keys [circuit-breaker] :as opts} f]
  (if circuit-breaker
    (make-attempt-with-circuit-breaker opts f)
    (make-attempt-direct opts f)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                   ----==| C O R E   S A F E L Y |==----                    ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn safely-fn
  "Safely offers a safe code execution against Exceptions.
   It offers a declarative approach to a large number of handling strategies.
   Usage:

          (safely-fn
             f
             & handling-options)

   The available handling options are:

     - `:default <value>`
        will return `<value>` if the execution of `<code>` fails.


   Available retry policies:

     - `:max-retries <n>` or `:forever`
        will retry the code block in case of failures for a maximum
        of `<n>` times. Since this express the 're-tries' you should assume
        the total number of attempts to be at most `n + 1`.
        When set to `:forever` it will retry indefinitely.
        Used in conjunction with `:default` will retry first, and if
        all attempts fails the default value will be returned instead.
        The time between each retry is determined by one of the
        following options, the default strategy is: `:random-exp-backoff`


     - `:retry-delay [:fix <millis>]` *(NOT RECOMMENDED)*
        To sleep a fix amount of time between retries.

     - `:retry-delay [:random-range :min <millis> :max <millis>]`
        To sleep a random amount of time between retries within
        certain a `:min` and `:max` time.

     - `:retry-delay [:random <millis> :+/- <pct>]`
        To sleep a random amount of time `<millis>` each retry which
        is randomized with a +/- `<pct>` of the base value.
        Eg: `:random 5000 :+/- 0.35` will sleep 5s with `+/- 35%`

     - `:retry-delay [:random-exp-backoff :base <millis> :+/- <pct>]`
       `:retry-delay [:random-exp-backoff :base <millis> :+/- <pct> :max <millis>]`
        To sleep a random amount of time which will exponentially
        grow between retries. (see documentation for more info)

     - `:retry-delay [:rand-cycle [<millis1> <millis2> ... <millisN>] :+/- <pct>]`
        To sleep cycling the given list and randomizing by +/- <pct>.
        On the first retry will wait `<millis1> +/- <pct>`, on the second
        retry will wait `<millis2> +/- <pct>` as so on. If the `:max-retries`
        exceeds the number of waiting time it will restart from `<millis1>`.

     - `:retryable-error? (fn [exception] true)`
        In cases where only certain type of errors can be retried but
        not others, you can define a function which takes in input
        the exception raised and returns whether this exception
        should be retried or not. If the error isn't retryable
        the exception will be thrown up to be handled outside
        of the safely block.
        For example if you wish not to retry ArithmeticException
        you could use something like:
        `:retryable-error? #(not (#{ArithmeticException} (type %)))`

     - `:failed? (fn [result] false)`
        You can provide a predicate function to determine whether the result
        of the body expression is a `failed` result of not.
        The failed predicate can be used to produce the same delayed retry
        with APIs which do not throw exceptions. For example consider a
        HTTP request which returns the status instead of failing.
        With the failed predicate function you could have exponential back-off
        retry when the HTTP response contains a HTTP status code which is not `2xx`.
        Another use of this is for example in APIs which support polling.
        The failed predicate function can be used to determine whether the polling
        call returned valid items or it was empty, and if it is empty then it is
        possible to slow down the polling using the default exponential back-off.
        The `:failed?` predicate function is executed only on successful body
        execution and only when provided. If `:failed?` returns true, then the
        execution is considered failed, even though there is no exception,
        and it will follow the exceptional retry logic as normal.

  Circuit breaker options:

     - `:circuit-breaker <:operation-name>`
        This options is required to activate the circuit breaker.
        It identifies the specific operation the circuit breaker is
        protecting. The name is used also to track resources and stats
        for the operation. NOTE: don't use high cardinality values or
        randomly generate values to avoid the risk of running out of
        memory. Name the circuit breaker after the operation it is
        trying to accomplish.

     - `:thread-pool-size  10`
        This is the size of the dedicated thread pool for this operation.
        The default size should work fine for most of high volume operations.
        Before changing this value please refer to the following link:
        https://github.com/BrunoBonacci/safely#how-to-size-the-thread-pool
        about how to correctly size circuit breaker thread pools.

     - `:queue-size 5`
        It sets how big should be the queue for the circuit breaker
        which it is in front of the thread pool. A good value for this
        is about 30%-50% of the thread pool size. The queue should be used
        only to cope with a small surge in requests. Be aware that the bigger
        is the queue the more latency will be added processing your requests.
        Before changing this value please refer to the following link:
        https://github.com/BrunoBonacci/safely#how-to-size-the-thread-pool
        about how to correctly size circuit breaker thread pools.

     - `:sample-size 100`
        It sets how big it is the buffer that samples the most recent
        requests. This it can be useful to see what happened to
        the recent requests and decide whether to trip the circuit open.

     - `:timeout 3000` *(in millis) (default: not set)*
        It sets a timeout on each individual request sent in the circuit
        breaker. It only works when used in conjunction with the circuit
        breaker. If not set the caller will wait until the thread has
        completed to process the request and returned a value.
        When set, if the thread process the request before the timeout
        expires the resulting value is returned to the caller, otherwise
        a timeout exception is thrown.

     - `:cancel-on-timeout :always` It controls what happen to the request
        when a timeout wait time is reached.  You can choose when you want
        to cancel the request. Available options are: `:never`,
        `:if-not-running`, `:always`. `:if-not-running` cancels the request
        only if it is still in the queue and the execution is not started
        yet.

     - `:counters-buckets 10`
        The number of 1-second buckets with counters for the number of
        requests succeeded, failed, timed out, etc. Only the most
        recent requests buckets are kept.

     - `:circuit-breaker-strategy :failure-threshold`
        This is the strategy used to trip the circuit breaker open.
        Currently only this strategy is supported.

     - `:failure-threshold 0.50` *(50%)*
        Only used when `:circuit-breaker-strategy` is `:failure-threshold`.
        It sets the threshold which when crossed, it will trip the
        circuit open. It requires at least 3 requests in the counters
        to evaluate the threshold. Otherwise it is closed by default.

     - `:grace-period 3000` *(in millis)*
        When the circuit is tripped open, it will reject all the requests
        within the grace period. After this period is passed then it will
        change state and go to the half-open state.

     - `:half-open-strategy :linear-ramp-up`
        When the circuit moves from `:open` state to `:half-open` the
        circuit breaker has to decide which requests to let through and
        which reject immediately.  This is the strategy used to evaluate
        which requests are to be tried in order to determine whether the
        circuit can be closed again.  Currently only this strategy is
        supported.

     - `:ramp-up-period 5000` *(in millis)*
        Only used when :half-open-strategy is `:linear-ramp-up`.
        The `:linear-ramp-up` will pick randomly a increasing number
        of requests and let them through and evaluate the result.


   Exceptions are logged automatically. Here some options to control logging

     - `:message \"a custom error message\"`
        To log the error with a custom message which helps to contextualize
        the error message.

     - `:log-errors false` *(default true)*
        To disable logging

     - `:log-level <level>` *(default :warn)*
        To log the errors with a given error level, available options:
        `:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`

     - `:log-stacktrace false` *(default true)*
        To disable stacktrace reporting in the logs.

     - `:log-ns \"your.namespace\"` *(default `safely.log`)*
        To select the namespace logger. It defaults to the `safely.log`.

   It is possible to track the number or and the rate of error automatically
   in your monitoring system of choice by just adding the name under which
   you want to track this error. By default is not enabled.

     - `:track-as \"myproject.errors.mymodule.myaction\"`
        Will use the given string as name for the metric. Use names which
        will be clearly specifying the which part of your code is failing
        for example: `\"app.errors.db.writes\"`
        and `\"app.errors.services.account.fetchuser\"` clearly specify
        which action is currently failing. The tracking is done via
        Samsara/TrackIt! (see: https://github.com/samsara/trackit)

  (see website for more documentation: https://github.com/BrunoBonacci/safely)
  "
  [f & {:as opts}]
  (let [;; applying defaults
        opts'   (apply-defaults opts defaults)
        ;; lazy execution as only needed in case of error
        delayer (delay (apply sleeper (:retry-delay opts')))
        ;; instrument inner call
        f       (fn [] (inner-tracker (:track-as opts') (f)))]
    (outer-tracker (:track-as opts')
      (loop [{:keys [message default max-retries attempt track-as
                     retryable-error? failed?] :as data} opts']
        (let [[result ex] (make-attempt opts' f)]
          ;; check execution outcome,
          ;; if it is not an error or a failed result, then..
          (if-not (or ex (failed? result))
            ;; it ran successfully
            result

            ;; else: we have an error
            ;; and we need to handle the outcome
            (cond
              ;; check whether is a retryable error only when the function is provided
              ;; and there is an actual error
              (and retryable-error? ex (not (retryable-error? ex)))
              (throw ex)

              ;; we reached the max retry but we have a default
              (and (not= ::undefined default) (>= attempt max-retries))
              default

              ;; we got error and reached the max retry
              (and (= ::undefined default) (>= attempt max-retries))
              (throw (ex-info message data ex))

              ;; retry
              :else
              (do
                (@delayer)
                (recur (update data :attempt inc))))))))))



(defn- deprecation-warning
  "It displays a deprecation warning message if a deprecated option is found. "
  [call-site options]
  (let [options (into #{} (keys (apply assoc {} options)))]
    (when (:max-retry options)
      (println "WARNING: Safely `:max-retry` options is deprecated in favour of `:max-retries`, please update your code in " call-site))))



(defmacro safely
  "Safely offers a safe code execution against Exceptions.
   It offers a declarative approach to a large number of handling strategies.
   Usage:

          (safely
             & code
             :on-error
             & handling-options)

   The available handling options are:

     - `:default <value>`
        will return `<value>` if the execution of `<code>` fails.


   Available retry policies:

     - `:max-retries <n>` or `:forever`
        will retry the code block in case of failures for a maximum
        of `<n>` times. Since this express the 're-tries' you should assume
        the total number of attempts to be at most `n + 1`.
        When set to `:forever` it will retry indefinitely.
        Used in conjunction with `:default` will retry first, and if
        all attempts fails the default value will be returned instead.
        The time between each retry is determined by one of the
        following options, the default strategy is: `:random-exp-backoff`


     - `:retry-delay [:fix <millis>]` *(NOT RECOMMENDED)*
        To sleep a fix amount of time between retries.

     - `:retry-delay [:random-range :min <millis> :max <millis>]`
        To sleep a random amount of time between retries within
        certain a `:min` and `:max` time.

     - `:retry-delay [:random <millis> :+/- <pct>]`
        To sleep a random amount of time `<millis>` each retry which
        is randomized with a +/- `<pct>` of the base value.
        Eg: `:random 5000 :+/- 0.35` will sleep 5s with `+/- 35%`

     - `:retry-delay [:random-exp-backoff :base <millis> :+/- <pct>]`
       `:retry-delay [:random-exp-backoff :base <millis> :+/- <pct> :max <millis>]`
        To sleep a random amount of time which will exponentially
        grow between retries. (see documentation for more info)

     - `:retry-delay [:rand-cycle [<millis1> <millis2> ... <millisN>] :+/- <pct>]`
        To sleep cycling the given list and randomizing by +/- <pct>.
        On the first retry will wait `<millis1> +/- <pct>`, on the second
        retry will wait `<millis2> +/- <pct>` as so on. If the `:max-retries`
        exceeds the number of waiting time it will restart from `<millis1>`.

     - `:retryable-error? (fn [exception] true)`
        In cases where only certain type of errors can be retried but
        not others, you can define a function which takes in input
        the exception raised and returns whether this exception
        should be retried or not. If the error isn't retryable
        the exception will be thrown up to be handled outside
        of the safely block.
        For example if you wish not to retry ArithmeticException
        you could use something like:
        `:retryable-error? #(not (#{ArithmeticException} (type %)))`

     - `:failed? (fn [result] false)`
        You can provide a predicate function to determine whether the result
        of the body expression is a `failed` result of not.
        The failed predicate can be used to produce the same delayed retry
        with APIs which do not throw exceptions. For example consider a
        HTTP request which returns the status instead of failing.
        With the failed predicate function you could have exponential back-off
        retry when the HTTP response contains a HTTP status code which is not `2xx`.
        Another use of this is for example in APIs which support polling.
        The failed predicate function can be used to determine whether the polling
        call returned valid items or it was empty, and if it is empty then it is
        possible to slow down the polling using the default exponential back-off.
        The `:failed?` predicate function is executed only on successful body
        execution and only when provided. If `:failed?` returns true, then the
        execution is considered failed, even though there is no exception,
        and it will follow the exceptional retry logic as normal.

  Circuit breaker options:

     - `:circuit-breaker <:operation-name>`
        This options is required to activate the circuit breaker.
        It identifies the specific operation the circuit breaker is
        protecting. The name is used also to track resources and stats
        for the operation. NOTE: don't use high cardinality values or
        randomly generate values to avoid the risk of running out of
        memory. Name the circuit breaker after the operation it is
        trying to accomplish.

     - `:thread-pool-size  10`
        This is the size of the dedicated thread pool for this operation.
        The default size should work fine for most of high volume operations.
        Before changing this value please refer to the following link:
        https://github.com/BrunoBonacci/safely#how-to-size-the-thread-pool
        about how to correctly size circuit breaker thread pools.

     - `:queue-size 5`
        It sets how big should be the queue for the circuit breaker
        which it is in front of the thread pool. A good value for this
        is about 30%-50% of the thread pool size. The queue should be used
        only to cope with a small surge in requests. Be aware that the bigger
        is the queue the more latency will be added processing your requests.
        Before changing this value please refer to the following link:
        https://github.com/BrunoBonacci/safely#how-to-size-the-thread-pool
        about how to correctly size circuit breaker thread pools.

     - `:sample-size 100`
        It sets how big it is the buffer that samples the most recent
        requests. This it can be useful to see what happened to
        the recent requests and decide whether to trip the circuit open.

     - `:timeout 3000` *(in millis) (default: not set)*
        It sets a timeout on each individual request sent in the circuit
        breaker. It only works when used in conjunction with the circuit
        breaker. If not set the caller will wait until the thread has
        completed to process the request and returned a value.
        When set, if the thread process the request before the timeout
        expires the resulting value is returned to the caller, otherwise
        a timeout exception is thrown.

     - `:cancel-on-timeout :always` It controls what happen to the request
        when a timeout wait time is reached.  You can choose when you want
        to cancel the request. Available options are: `:never`,
        `:if-not-running`, `:always`. `:if-not-running` cancels the request
        only if it is still in the queue and the execution is not started
        yet.

     - `:counters-buckets 10`
        The number of 1-second buckets with counters for the number of
        requests succeeded, failed, timed out, etc. Only the most
        recent requests buckets are kept.

     - `:circuit-breaker-strategy :failure-threshold`
        This is the strategy used to trip the circuit breaker open.
        Currently only this strategy is supported.

     - `:failure-threshold 0.50` *(50%)*
        Only used when `:circuit-breaker-strategy` is `:failure-threshold`.
        It sets the threshold which when crossed, it will trip the
        circuit open. It requires at least 3 requests in the counters
        to evaluate the threshold. Otherwise it is closed by default.

     - `:grace-period 3000` *(in millis)*
        When the circuit is tripped open, it will reject all the requests
        within the grace period. After this period is passed then it will
        change state and go to the half-open state.

     - `:half-open-strategy :linear-ramp-up`
        When the circuit moves from `:open` state to `:half-open` the
        circuit breaker has to decide which requests to let through and
        which reject immediately.  This is the strategy used to evaluate
        which requests are to be tried in order to determine whether the
        circuit can be closed again.  Currently only this strategy is
        supported.

     - `:ramp-up-period 5000` *(in millis)*
        Only used when :half-open-strategy is `:linear-ramp-up`.
        The `:linear-ramp-up` will pick randomly a increasing number
        of requests and let them through and evaluate the result.


   Exceptions are logged automatically. Here some options to control logging

     - `:message \"a custom error message\"`
        To log the error with a custom message which helps to contextualize
        the error message.

     - `:log-errors false` *(default true)*
        To disable logging

     - `:log-level <level>` *(default :warn)*
        To log the errors with a given error level, available options:
        `:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`

     - `:log-stacktrace false` *(default true)*
        To disable stacktrace reporting in the logs.

     - `:log-ns \"your.namespace\"` (default `*ns*`)
        To select the namespace logger. It defaults to the current ns.

   It is possible to track the number or and the rate of error automatically
   in your monitoring system of choice by just adding the name under which
   you want to track this error. By default is not enabled.

     - `:track-as \"myproject.errors.mymodule.myaction\"`
        Will use the given string as name for the metric. Use names which
        will be clearly specifying the which part of your code is failing
        for example: `\"app.errors.db.writes\"`
        and `\"app.errors.services.account.fetchuser\"` clearly specify
        which action is currently failing. The tracking is done via
        Samsara/TrackIt! (see: https://github.com/samsara/trackit)

  (see website for more documentation: https://github.com/BrunoBonacci/safely)
  "
  {:style/indent 1
   :arglists '([& body :on-error & handling-options])}
  [& code]
  (let [;; detecting call site
        {:keys [line column]} (meta &form)
        call-site# (str *ns* "[l:" line ", c:" column "]")
        ;; checking options format
        [body _ options :as seg] (partition-by #{:on-error} code)]
    (deprecation-warning call-site# options)
    (if (not= 3 (count seg))
      (throw (IllegalArgumentException.
              "Missing or invalid ':on-error' clause."))
      `(safely-fn
        (fn []
          ~@body)
        :log-ns *ns*
        :call-site ~call-site#
        ~@options))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;         ----==| C I R C U I T   B R E A K E R   T O O L S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn shutdown-pools
  "It shuts down, forcefully, all the circuit-breaker active pools.
   If you provide a `pool-name` it will shutdown only the specified one."
  ([]
   (safely.circuit-breaker/shutdown-pools))
  ([pool-name]
   (safely.circuit-breaker/shutdown-pools pool-name)))



(defn circuit-breaker-info
  "It returns a map with information regarding one circuit breaker
   (if a name is specified) or all of them. the structure contains
    the status, some counters, and sampled responses."
  ([]
   (safely.circuit-breaker/circuit-breaker-info))
  ([circuit-breaker-name]
   (safely.circuit-breaker/circuit-breaker-info circuit-breaker-name)))
