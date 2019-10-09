(ns ^:no-doc safely.circuit-breaker
  "Internal circuit breaker functions"
  (:require [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]
            [defun :refer [defun]]
            [safely.thread-pool
             :refer [async-execute-with-pool timeout-wait
                     fixed-thread-pool running-task-count]])
  (:import java.util.concurrent.ThreadPoolExecutor))

(defun now
  "Returns the current system clock time as number of
   seconds or milliseconds from EPOCH."
  ([]
   (now :millis))
  ([:seconds]
   (quot (System/currentTimeMillis) 1000))
  ([:millis]
   (System/currentTimeMillis)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;         ----==| C I R C U I T - B R E A K E R   S T A T S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- update-samples
  "Add the last request outcome into a ring-buffer of request samples.
  When the buffer is full the oldest request will be dropped.  It
  records the `:timestamp` of when the request was made, when an
  exception is thrown `:error` captures the actual exception and
  `:failure` is type of error which it can be one of:

    - `:error` when an exception is raised inside the user's code
    - `:timeout` when the request times out
    - `:queue-full` when the request is rejected because the circuit
                    breaker queue is full.
    - `:circuit-open` when the request is rejected because the circuit
                    breaker queue is open.
    - `nil` for a successful request.
  "
  [{{:keys [sample-size]} :config :as state} timestamp [_ fail error]]
  ;; don't add requests which didn't enter the c.b.
  (if (= fail :circuit-open)
    state
    (update state
            :samples
            (fnil conj (ring-buffer sample-size))
            {:timestamp timestamp
             :failure   fail
             :error     error})))



(defn- update-counters
  "It updates the counters for the last x seconds incrementing
   the counter for the given request outcome."
  [{{:keys [counters-buckets]} :config :as state} timestamp [ok fail]]
  (update state
          :counters
          (fn [counters]
            (let [ts     (quot timestamp 1000)
                  min-ts (- ts counters-buckets)]
              (as-> (or counters (sorted-map)) $
                (update $ ts
                        (fn [{:keys
                             [success error timeout rejected open] :as p-counters}]
                          (let [p-counters (or p-counters
                                              {:success  0, :error  0, :timeout  0,
                                               :rejected 0, :open   0})]
                            (case fail

                              nil
                              (update p-counters :success inc)

                              :error
                              (update p-counters :error inc)

                              :timeout
                              (update p-counters :timeout inc)

                              :queue-full
                              (update p-counters :rejected inc)

                              :circuit-open
                              (update p-counters :open inc)))))

                ;; keep only last `counters-buckets` entries
                (apply dissoc $ (filter #(< % min-ts) (map first $))))))))



(defn- update-stats
  [state result]
  (let [ts (now)]
    (-> state
        (update-samples  ts result)
        (update-counters ts result))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;    ----==| C I R C U I T   B R E A K E R   S T R A T E G I E S |==----     ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn sum-counters
  ([] {:success 0, :error 0, :timeout 0, :rejected 0, :open 0})
  ([c1] c1)
  ([{s1 :success, e1 :error, t1 :timeout, r1 :rejected, o1 :open}
    {s2 :success, e2 :error, t2 :timeout, r2 :rejected, o2 :open}]
   {:success (+ s1 s2), :error (+ e1 e2),
    :timeout (+ t1 t2), :rejected (+ r1 r2),
    :open (+ o1 o2)}))



(defn counters-totals
  "Takes in input a map of counters as defined in the cb-state,
   and the number of seconds to take into consideration and
   it returns the total of each metric for the given time range."
  [counters last-n-seconds]
  (->> counters
       ;; take only last 10 seconds
       (filter #(> (first %) (- (now :seconds) last-n-seconds)))
       (map second)
       (reduce sum-counters)))



(defmulti evaluate-state
  "This function takes a circuit-breaker state value and depending
  of `:circuit-breaker-strategy` in the `:config` it select a
  different evaluation strategy to determine whether the circuit
  breaker should be closed (`true`) or open (`false`)"
  (comp :circuit-breaker-strategy :config))



(defmethod evaluate-state :failure-threshold
  [{:keys [counters]
    {:keys [failure-threshold counters-buckets]} :config}]
  (let [{:keys [success, error, timeout, rejected]} (counters-totals counters counters-buckets)
        failures (+ error, timeout, rejected)
        total    (+ success failures)]
    (cond
      ;; if no requests (or too few) are counted then it is closed
      (< total 3) true
      ;; if the failures % is bigger than the threshold
      ;; then trip the circuit open
      (> (/ failures total) failure-threshold) false
      ;; otherwise it is still closed.
      :else true)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;           ----==| A L L O W - T H I S - R E Q U E S T ? |==----            ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defmulti allow-this-request?
  (fn [{:keys [status]
       {:keys [half-open-strategy]} :config}]
    (if (= status :half-open)
      [status half-open-strategy]
      [status])))



(defmethod allow-this-request? [:closed]
  [_]
  true)



(defmethod allow-this-request? [:open]
  [_]
  false)



(defmethod allow-this-request? [:half-open :linear-ramp-up]
  [{:keys [last-status-change]
    {:keys [ramp-up-period]} :config}]
  (let [probability (/ (- (now) (or last-status-change 0)) ramp-up-period)]
    (< (rand) probability)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;            ----==| C B   P O O L S   A N D   S T A T E |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  ;; cb state (example)
  {:cb-name1
   (atom
    {:status :closed
     :last-status-change 1509199400

     ;; injected values
     :in-flight 3

     :counters {1509199799 {:success 1, :error 0, :timeout 1, :rejected 0, :open 0}},
     :samples [{:timestamp 1509199799102, :failure nil :error nil}
               {:timestamp 1509199799348, :failure :timeout :error nil}]
     :config {} ;; safely config
     })}
  )



(defn- circuit-breaker-state-init
  [{:keys [sample-size] :as options}]
  (atom
   {:status             :closed
    :last-status-change (now)
    :samples            (ring-buffer sample-size)
    :counters           {}
    :config             options}))



(defn- -pool
  "It returns a circuit breaker pool with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [cb-pools-atom {:keys [circuit-breaker thread-pool-size queue-size]}]
  (if-let [p (get @cb-pools-atom (keyword circuit-breaker))]
    p
    (-> cb-pools-atom
        (swap!
         update (keyword circuit-breaker)
         (fn [thread-pool]
           ;; might be already set by another
           ;; concurrent thread.
           (or thread-pool
              ;; if it doesn't exists then create one and initialize it.
              (fixed-thread-pool
               (str "safely.cb." (name circuit-breaker))
               thread-pool-size :queue-size queue-size))))
        (get (keyword circuit-breaker)))))



(defn- -circuit-breaker-state
  "It returns a circuit breaker state atom with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [cb-state-atom {:keys [circuit-breaker] :as options}]
  (let [circuit-breaker (keyword circuit-breaker)]
    (if-let [s (get @cb-state-atom circuit-breaker)]
      s
      (-> cb-state-atom
          (swap!
           update circuit-breaker
           (fn [state]
             ;; might be already set by another
             ;; concurrent thread.
             (or state
                ;; if it doesn't exists then create one and initialize it.
                (circuit-breaker-state-init options))))
          (get circuit-breaker)))))



(defn- -shutdown-pools
  "It shuts down, forcefully, all the circuit-breaker active pools.
   If you provide a `pool-name` it will shutdown only the specified one."
  ([cb-pools-atom]
   (->> @cb-pools-atom
        (run! (fn [[k# ^ThreadPoolExecutor tp#]]
                (log/info "shutting down pool:" k#)
                (.shutdownNow tp#)))))
  ([cb-pools-atom pool-name]
   (some-> (get @cb-pools-atom pool-name)
           ((fn [[k# ^ThreadPoolExecutor tp#]]
              (log/info "shutting down pool:" k#)
              (.shutdownNow tp#))))))



(defn- -circuit-breaker-info
  "It returns a map with information regarding one circuit breaker
   (if a name is specified) or all of them. the structure contains
    the status, some counters, and sampled responses."
  ([cb-state-atom cb-pool-atom]
   (->> (merge-with vector @cb-state-atom @cb-pool-atom)
        (map (fn [[k [v1 v2]]]
               ;; inject the in-flight requests
               [k (assoc @v1 :in-flight
                         (running-task-count v2))]))
        (into {})))
  ([cb-state-atom cb-pool-atom cb-name]
   (let [^ThreadPoolExecutor tp (get @cb-pool-atom cb-name)]
     (some-> @cb-state-atom
             (get cb-name)
             deref
             (assoc :in-flight (running-task-count tp))))))


;;
;; `cb-state` and `cb-pools` contains respectively the state information
;; for all circuit breakers as a map of atom so that independent calls
;; to separate circuit breakers won't conflict and can be independently
;; update their own state. This are meant to be private and not used
;; by the final user. For this reason public function which are meant
;; to manipulate these atoms are provided as closures over this data.
;;
(let [cb-state (atom {})
      cb-pools (atom {})]

  (defn- pool
    "It returns a circuit breaker pool with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
    [cb-options]
    (-pool cb-pools cb-options))



  (defn- circuit-breaker-state
    "It returns a circuit breaker state atom with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
    [cb-options]
    (-circuit-breaker-state cb-state cb-options))


  (defn shutdown-pools
    "It shuts down, forcefully, all the circuit-breaker active pools.
     If you provide a `pool-name` it will shutdown only the specified one."
    ([]
     (-shutdown-pools cb-pools))
    ([pool-name]
     (-shutdown-pools cb-pools pool-name)))


  (defn circuit-breaker-info
    "It returns a map with information regarding one circuit breaker
    (if a name is specified) or all of them. the structure contains
    the status, some counters, and sampled responses."
    ([]
     (-circuit-breaker-info cb-state cb-pools))
    ([circuit-breaker-name]
     (-circuit-breaker-info cb-state cb-pools circuit-breaker-name)))

  )




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ----==| S T A T E   T R A N S I T I O N S |==----              ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defmulti  transition-state :status)



(defmethod transition-state :closed
  [state]
  (let [closed? (evaluate-state state)]
    (if closed?
      state
      (assoc state
             :status   :open ;; change state
             :counters {}    ;; reset counters
             ))))



(defmethod transition-state :open
  [{:keys [last-status-change]
    {:keys [grace-period]} :config :as state} ]
  (let [;; calculate the number of seconds elapsed
        elapsed (- (now) (or last-status-change 0))]
    (if (> elapsed grace-period)
      (assoc state :status :half-open)
      state)))



(defmethod transition-state :half-open
  [{:keys [last-status-change]
    {:keys [ramp-up-period]} :config :as state} ]
  (let [ ;; calculate the number of seconds elapsed
        elapsed (- (now) (or last-status-change 0))
        closed? (evaluate-state state)]
    (cond
      ;; if rampup time is completed and status is ok,
      ;; then close the circuit
      (and (> elapsed ramp-up-period) closed?) (assoc state :status :closed)
      ;; if requests are failing then reopen (even if ramp-up is not completed)
      (not closed?) (assoc state :status :open)
      ;; otherwise just continue with half-open
      :else state)))



(defn- update-state
  [state result]
  (as-> state $
    (update-stats $ result)
    (transition-state $)
    ;; If the status changed then let's update the timestamp
    (if (not= (:status state) (:status $))
      (assoc $ :last-status-change (now))
      $)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                     ----==| E X E C U T I O N |==----                      ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn execute-with-circuit-breaker
  "Execute a thunk `f` in a circuit-breaker pool"
  [f {:keys [circuit-breaker timeout
             cancel-on-timeout] :as options}]
  {:pre [(not (nil? circuit-breaker))]}
  (let [state  (circuit-breaker-state options)
        state' (deref state)
        result
        ;;  check if circuit is open or closed.
        (if (allow-this-request? state')
          ;; retrieve or create thread-pool
          (-> (pool options)
              ;; executed in thread-pool and get a promise of result
              (async-execute-with-pool f)
              ;; wait result or timeout to expire
              (timeout-wait timeout cancel-on-timeout))

          ;; ELSE
          [nil :circuit-open nil])]
    ;; update stats
    (swap! state update-state result)
    ;; return result
    result))
