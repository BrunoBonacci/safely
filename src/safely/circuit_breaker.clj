(ns safely.circuit-breaker
  (:require [safely.thread-pool :refer
             [fixed-thread-pool execute-with-pool]]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]))



(def cb-stats (atom {}))



(defn execute-with-circuit-breaker
  [pool cb-name f {:keys [timeout sample-size] :as options}]
  (let [[_ fail :as  result] (execute-with-pool pool timeout f)]
    (swap! cb-stats update cb-name
           (fnil conj (ring-buffer sample-size))
           {:timestamp (System/currentTimeMillis)
            :failure   (if (instance? Exception fail) :error fail)
            :error     (when (instance? Exception fail) fail)})
    result))





(comment

  ;; TODO: create pool and add it if not exists
  ;; TODO: add function to evaluate samples
  ;;       and open/close circuit
  ;; TODO: refactor metrics
  ;; TODO: add documentation
  ;; TODO: cancel timed out tasks.

  (def ^java.util.concurrent.ExecutorService pool
    (fixed-thread-pool "safely.test" 5 :queue-size 5))


  (def f (fn []
           (println "long running job")
           (Thread/sleep (rand-int 5000))
           (if (< (rand) 1/3)
             (throw (ex-info "boom" {}))
             (rand-int 1000))))


  (execute-with-circuit-breaker
   pool "safely.test" f
   {:sample-size 10 :timeout 3000})



  (->> @cb-stats
       first
       second
       (map (fn [x] (dissoc x :error))))

  )
