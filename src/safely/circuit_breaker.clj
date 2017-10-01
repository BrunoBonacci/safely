(ns safely.circuit-breaker
  (:require [safely.thread-pool :refer
             [fixed-thread-pool execute-with-pool]]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]))



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
           (fnil conj (ring-buffer sample-size))
           {:timestamp (System/currentTimeMillis)
            :failure   fail
            :error     error})
    result))



(comment

  ;; TODO: add function to evaluate samples
  ;;       and open/close circuit
  ;; TODO: refactor metrics
  ;; TODO: add documentation
  ;; TODO: cancel timed out tasks.

  (def p (pool {:circuit-breaker :safely.test
                :queue-size 5 :thread-pool-size 5}))

  cb-pools


  (def f (fn []
           (println "long running job")
           (Thread/sleep (rand-int 5000))
           (if (< (rand) 1/3)
             (throw (ex-info "boom" {}))
             (rand-int 1000))))


  (execute-with-circuit-breaker
   f
   {:circuit-breaker "test" :sample-size 10 :timeout 3000
    :thread-pool-size 5 :queue-size 5})



  (->> @cb-stats
       first
       second
       (map (fn [x] (dissoc x :error))))

  (keys @cb-stats)

  )
