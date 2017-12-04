(ns safely.circuit-breaker
  (:require [safely.thread-pool :refer
             [fixed-thread-pool async-execute-with-pool]]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;          ---==| C I R C U I T - B R E A K E R   S T A T S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- update-samples
  ;;TODO: doc
  [stats timestamp [_ fail error] {:keys [sample-size]}]
  ;; don't add requests which didn't enter the c.b.
  (if (= fail :circuit-open)
    stats
    (update stats
            :samples
            (fnil conj (ring-buffer sample-size))
            {:timestamp timestamp
             :failure   fail
             :error     error})))



(defn- update-counters
  ;;TODO: doc
  [stats timestamp [ok fail] {:keys [sample-size counters-buckets]}]
  (update stats
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
  [stats result opts]
  (let [ts (System/currentTimeMillis)]
    (-> stats
        (update-samples  ts result opts)
        (update-counters ts result opts)
        (update :in-flight (fnil dec 0)))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                          ---==| P O O L S |==----                          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  ;; cb stats
  {:cb-name1
   (atom
    {:status :closed
     :in-flight 0
     :last-status-change 1509199400

     :counters {1509199799 {:success 0, :error 1, :timeout 0, :rejected 0, :open 0}},
     :samples [{:timestamp 1, :failure nil :error nil}
               {:timestamp 2, :failure :timeout :error nil}]
     })}
  )

(def cb-stats (atom {}))
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



(defn- circuit-breaker-stats
  "It returns a circuit breaker stats atom with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [{:keys [circuit-breaker sample-size] :as options}]
  (if-let [s (get @cb-stats (keyword circuit-breaker))]
    s
    (-> cb-stats
        (swap!
         update (keyword circuit-breaker)
         (fn [stats]
           ;; might be already set by another
           ;; concurrent thread.
           (or stats
              ;; if it doesn't exists then create one and initialize it.
              (atom
               {:status :closed :in-flight 0
                :last-status-change (System/currentTimeMillis)
                :samples (ring-buffer sample-size)
                :counters {}
                :config options}))))
        (get (keyword circuit-breaker)))))



(defn- should-allow-next-request?
  [stats-atom]
  (as-> stats-atom $
    (swap! $ (fn [{{:keys [circuit-closed?]} :config :as stats} ]
               (let [closed? (circuit-closed? stats)]
                 (as-> stats $
                   ;; update status
                   (update $ :status (fn [os] (if closed? :closed :open)))
                   ;; If the status changed then let's update the timestamp
                   (if (not= (:status stats) (:status $))
                     (assoc $ :last-status-change (System/currentTimeMillis))
                     $)
                   ;; if circuit is closed then increment the number of
                   ;; in flight requests.
                   (if closed?
                     (update $ :in-flight (fnil inc 0))
                     $)))))
    (= :closed (:status $))))



(defn execute-with-circuit-breaker
  [f {:keys [circuit-breaker timeout] :as options}]
  (let [stats (circuit-breaker-stats options)
        result
        ;;  check if circuit is open or closed.
        (if (should-allow-next-request? stats)
          ;; retrieve or create thread-pool
          (-> (pool options)
              ;; executed in thread-pool and get a promise of result
              (async-execute-with-pool f)
              ;; wait result or timeout to expire
              (deref timeout [nil :timeout nil]))

          ;; ELSE
          [nil :circuit-open nil])]
    ;; update stats
    (swap! stats update-stats result options)
    ;; return result
    result))




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



(defn closed?-by-failure-threshold
  [{:keys [status counters]
    {:keys [failure-threshold counters-buckets]} :config}]
  (let [ts (quot (System/currentTimeMillis) 1000)
        tot-counters (->> counters
                          ;; take only last 10 seconds
                          (filter #(> (first %) (- ts counters-buckets)))
                          (map second)
                          (reduce sum-counters))
        {:keys [success, error, timeout, rejected]} tot-counters
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


(comment

  ;; TODO: add function to evaluate samples
  ;;       and open/close circuit
  ;; TODO: refactor metrics
  ;; TODO: add documentation
  ;; TODO: cancel timed out tasks.

  (def p (pool {:circuit-breaker :safely.test
                :queue-size 5 :thread-pool-size 5
                :sample-size 20 :counters-buckets 10}))

  cb-pools


  (def f (fn []
           (println "long running job")
           (Thread/sleep (rand-int 3000))
           (if (< (rand) 1/3)
             (throw (ex-info "boom" {}))
             (rand-int 1000))))


  (execute-with-circuit-breaker
   f
   {:circuit-breaker :safely.test
    :thread-pool-size 10
    :queue-size       5
    :sample-size      100
    :timeout          3000
    :counters-buckets 10
    :circuit-closed?  closed?-by-failure-threshold
    :failure-threshold 0.5})


  (as-> @cb-stats $
    (:safely.test $)
    (deref $)
    )

  (as-> @cb-stats $
    (:test $)
    (deref $)
    (:counters $)
    )

  (as-> @cb-stats $
    (:safely.test $)
    (deref $)
    (:counters $)
    (map second $)
    (reduce sum-counters $))



  (->> @cb-stats
       first
       second
       deref
       :samples
       (map (fn [x] (dissoc x :error))))

  (keys @cb-stats)


  (require '[safely.core])

  (safely.core/safely
   (println "long running job")
   (Thread/sleep (rand-int 3000))
   (if (< (rand) 1/3)
     (throw (ex-info "boom" {}))
     (rand-int 1000))
   :on-error
   :circuit-breaker :safely.test
   :thread-pool-size 10
   :queue-size       5
   :sample-size      100
   :timeout          2000
   :counters-buckets 10
   :circuit-closed?  closed?-by-failure-threshold
   :failure-threshold 0.5)


  )
