(ns safely.circuit-breaker
  (:require [amalloy.ring-buffer :refer [ring-buffer]]
            [defun :refer [defun]]
            [safely.thread-pool :refer [async-execute-with-pool fixed-thread-pool]]))



(defun now
  ([]
   (now :millis))
  ([:seconds]
   (quot (System/currentTimeMillis) 1000))
  ([:millis]
   (System/currentTimeMillis)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;          ---==| C I R C U I T - B R E A K E R   S T A T S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- update-samples
  ;;TODO: doc
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
  ;;TODO: doc
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
        (update-counters ts result)
        (update :in-flight (fnil dec 0)))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;     ---==| C I R C U I T   B R E A K E R   S T R A T E G I E S |==----     ;;
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
  ;; TODO doc
  [counters last-n-seconds]
  (->> counters
       ;; take only last 10 seconds
       (filter #(> (first %) (- (now :seconds) last-n-seconds)))
       (map second)
       (reduce sum-counters)))



(defmulti evaluate-state (comp :circuit-breaker-strategy :config))



(defmethod evaluate-state :failure-threshold
  [{:keys [status counters]
    {:keys [failure-threshold counters-buckets]} :config}]
  (let [{:keys [success, error, timeout, rejected]} (counters-totals counters counters-buckets)
        failures (+ error, timeout, rejected)
        total    (+ success failures)]
    (cond
      ;; if no requests are counted then it is open
      (== 0 total) true
      ;; if the failures % is bigger than the threshold
      ;; then trip the circuit open
      (> (/ failures total) failure-threshold) false
      ;; otherwise it is still closed.
      :else true)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;            ---==| A L L O W - T H I S - R E Q U E S T ? |==----            ;;
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



(defmethod allow-this-request? [:half-open :linear-rampup]
  [{:keys [last-status-change]
    {:keys [ramp-up-period]} :config}]
  (let [probability (/ (- (now) (or last-status-change 0)) ramp-up-period)]
    (< (rand) probability)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| C B   P O O L S   A N D   S T A T E |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  ;; cb state
  {:cb-name1
   (atom
    {:status :closed
     :in-flight 0
     :last-status-change 1509199400

     :counters {1509199799 {:success 0, :error 1, :timeout 0, :rejected 0, :open 0}},
     :samples [{:timestamp 1, :failure nil :error nil}
               {:timestamp 2, :failure :timeout :error nil}]
     :config {} ;; safely block config
     })}
  )



(def cb-state (atom {}))



(def cb-pools (atom {}))



(defn pool
  "It returns a circuit breaker pool with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [{:keys [circuit-breaker thread-pool-size queue-size]}]
  (if-let [p (get @cb-pools (keyword circuit-breaker))]
    p
    (-> cb-pools
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



(defn- circuit-breaker-state
  "It returns a circuit breaker state atom with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [{:keys [circuit-breaker sample-size] :as options}]
  (if-let [s (get @cb-state (keyword circuit-breaker))]
    s
    (-> cb-state
        (swap!
         update (keyword circuit-breaker)
         (fn [state]
           ;; might be already set by another
           ;; concurrent thread.
           (or state
              ;; if it doesn't exists then create one and initialize it.
              (atom
               {:status :closed :in-flight 0
                :last-status-change (now)
                :samples (ring-buffer sample-size)
                :counters {}
                :config options}))))
        (get (keyword circuit-breaker)))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;              ---==| S T A T E   T R A N S I T I O N S |==----              ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defmulti  transition-state :status)



(defmethod transition-state :closed
  [state]
  (let [closed? (evaluate-state state)]
    (if closed?
      state
      (assoc state :status :open))))



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
    {:keys [ramp-up-seconds]} :config :as state} ]
  (let [ ;; calculate the number of seconds elapsed
        elapsed (quot (- (now) (or last-status-change 0)) 1000)
        closed? (evaluate-state state)]
    (cond
      ;; if rampup time is completed and status is ok,
      ;; then close the circuit
      (and (> elapsed :ramp-up-seconds) closed?) (assoc state :status :closed)
      ;; if requests are failing then reopen
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
;;                      ---==| E X E C U T I O N |==----                      ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn execute-with-circuit-breaker
  [f {:keys [circuit-breaker timeout] :as options}]
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
              (deref timeout [nil :timeout nil]))

          ;; ELSE
          [nil :circuit-open nil])]
    ;; update stats
    (swap! state update-state result)
    ;; return result
    result))
