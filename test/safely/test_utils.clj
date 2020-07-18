(ns safely.test-utils
  (:require [midje.sweet :refer [after before fact provided with-state-changes]]
            [safely.core :refer [shutdown-pools]])
  (:import clojure.lang.ExceptionInfo))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                     ---==| T E S T   U T I L S |==----                     ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *counter* nil)



(defn count-passes []
  (swap! *counter* inc))



(defn boom []
  (throw (ex-info "BOOOOM!" {:cause :boom})))



(defmacro count-attempts [body]
  (let [body# `(~(first body) (count-passes) ~@(next body))]
    `(binding [*counter* (atom 0)
               safely.core/*sleepless-mode* true]
       [~body#
        @*counter*])))



(defmacro sleepless [& body]
  `(binding [safely.core/*sleepless-mode* true]
     ~@body))



(defn crash-boom-bang!
  "a utility function which calls the first function in fs
  the first time is called, it calls the second function
  the second time is called and so on. It throws an Exception
  if no more functions are available to fs in a given call."
  [& fs]

  (let [xfs (atom fs)]
    (fn [& args]
      (let [f (or (first @xfs) (fn [&x] (throw (ex-info "No more functions available to call" {:cause :no-more-fn}))))
            _ (swap! xfs next)]
        (apply f args)))))



(defn uuid []
  (str (java.util.UUID/randomUUID)))



(defmacro run-thread [& body]
  `(let [result# (promise)]
     (.start
       (Thread.
         (fn []
           (try
             (deliver result# (do ~@body))
             (catch Throwable x#
               (deliver result# x#))))))
     result#))



(defmacro fact-with-test-pools
  [& body]
  `(let [cb-pools# (atom {})
         cb-state# (atom {})]
     (with-redefs [safely.circuit-breaker/pool
                   (fn [cb-options#]
                     (#'safely.circuit-breaker/-pool cb-pools# cb-options#))

                   safely.circuit-breaker/shutdown-pools
                   (fn []
                     (#'safely.circuit-breaker/-shutdown-pools cb-pools#))

                   safely.circuit-breaker/circuit-breaker-state
                   (fn [cb-options#]
                     (#'safely.circuit-breaker/-circuit-breaker-state cb-state# cb-options#))

                   safely.circuit-breaker/circuit-breaker-info
                   (fn
                     ([]
                      (#'safely.circuit-breaker/-circuit-breaker-info cb-state# cb-pools#))
                     ([name#]
                      (#'safely.circuit-breaker/-circuit-breaker-info cb-state# cb-pools# name#)))]
       (with-state-changes

         [(before :facts
            (do
              (reset! cb-pools# {})
              (reset! cb-state# {})))
          (after :facts (comment (safely.circuit-breaker/shutdown-pools)))]

         (fact ~@body)))))



(defmacro with-parallel
  [n & body]
  `(let [simplify-errors# (fn [e#]
                            (cond
                              (not (instance? ExceptionInfo e#)) e#
                              (nil? (:cause (ex-data (.getCause ^ExceptionInfo e#)))) e#
                              :else (:cause (ex-data (.getCause ^ExceptionInfo e#)))))
         semaphore# (promise)
         results# (->> (range ~n)
                    (map
                        (fn [_#]
                          (run-thread
                            @semaphore#
                            ~@body)))
                    (doall))]
     (deliver semaphore# :ok)
     (map (comp simplify-errors# deref) results#)))



(defmacro simple-result
  [& body]
  `(let [simplify-errors# (fn [e#]
                            (cond
                              (not (instance? ExceptionInfo e#)) e#
                              (nil? (:cause (ex-data (.getCause ^ExceptionInfo e#)))) e#
                              :else (:cause (ex-data (.getCause ^ExceptionInfo e#)))))]
     (simplify-errors#
       (try
         ~@body
         (catch Throwable x#
           x#)))))



(defn exception?
  [x]
  (instance? java.lang.Exception x))
