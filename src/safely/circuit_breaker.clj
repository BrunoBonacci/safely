(ns safely.circuit-breaker
  (:require [safely.thread-pool :refer
             [fixed-thread-pool execute-with-pool]]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;          ---==| C I R C U I T - B R E A K E R   S T A T S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- update-samples
  [stats timestamp [_ fail error] {:keys [sample-size]}]
  (update stats
          :samples
          (fnil conj (ring-buffer sample-size))
          {:timestamp timestamp
           :failure   fail
           :error     error}))



(defn- update-counters
  [stats timestamp [ok fail] {:keys [sample-size window-time-size]}]
  (update stats
          :counters
          (fn [cnts]
            (as-> (or cnts (sorted-map)) $
              (update $ (quot timestamp 1000)
                      (fn [{:keys [success error
                                  timeout rejected]}]
                        (let [success  (or success 0)
                              error    (or error 0)
                              timeout  (or timeout 0)
                              rejected (or rejected 0)]

                          (case fail

                            nil
                            {:success   (inc success)
                             :error     error
                             :timeout   timeout
                             :rejected  rejected}

                            :error
                            {:success   success
                             :error     (inc error)
                             :timeout   timeout
                             :rejected  rejected}

                            :timeout
                            {:success   success
                             :error     error
                             :timeout   (inc timeout)
                             :rejected  rejected}

                            :queue-full
                            {:success   success
                             :error     error
                             :timeout   timeout
                             :rejected  (inc rejected)}))))
              (if (> (count $) window-time-size)
                (dissoc $ (ffirst $))
                $)))))



(defn- update-stats
  [stats result opts]
  (let [ts (System/currentTimeMillis)]
    (-> stats
        (update-samples ts result opts)
        (update-counters ts result opts))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                          ---==| P O O L S |==----                          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


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



(defn execute-with-circuit-breaker
  [f {:keys [circuit-breaker timeout sample-size] :as options}]
  (let [tp (pool options) ;; retrieve thread-pool
        [_ fail error :as  result] (execute-with-pool tp timeout f)]
    (swap! cb-stats update (keyword circuit-breaker)
           update-stats result options)
    result))



(comment

  ;; TODO: add function to evaluate samples
  ;;       and open/close circuit
  ;; TODO: refactor metrics
  ;; TODO: add documentation
  ;; TODO: cancel timed out tasks.

  (def p (pool {:circuit-breaker :safely.test
                :queue-size 5 :thread-pool-size 5
                :sample-size 20 :window-time-size 10}))

  cb-pools


  (def f (fn []
           (println "long running job")
           (Thread/sleep (rand-int 5000))
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
    :window-time-size 10})



  (->> @cb-stats
       first
       second
       :samples
       (map (fn [x] (dissoc x :error))))

  (keys @cb-stats)

  )
