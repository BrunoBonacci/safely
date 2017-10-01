(ns safely.thread-pool
  (:import [java.util.concurrent
            ThreadPoolExecutor TimeUnit
            ThreadFactory ArrayBlockingQueue
            ExecutorService RejectedExecutionException]))


;; TODO: default uncaught-exception-handler
;; TODO: thread-groups and thread priority?


(defn thread-factory
  "Creates a thread factory which creates threads
   with the given prefix and a incremental counter.
   The threads can be created as daemon."
  [prefix daemon uncaught-exception-handler]
  (let [counter (atom 0)
        handler (reify Thread$UncaughtExceptionHandler
                  (^void uncaughtException
                   [_ ^Thread t, ^Throwable x]
                   (when uncaught-exception-handler
                     (uncaught-exception-handler t x))))]
    (reify ThreadFactory
      (^Thread newThread [_ ^Runnable r]
       (let [name (format "%s[%04d]" prefix (swap! counter inc))]
         (doto (Thread. r name)
           (.setDaemon daemon)
           (.setUncaughtExceptionHandler handler)))))))



(defn thread-pool
  "Creates a thread pool."
  [{:keys [name core-size max-size keep-alive-time queue-size]
    :or   {name "safely.unnamed" core-size 5 max-size 10
           keep-alive-time 60000 queue-size 50}} ]
  (ThreadPoolExecutor.
   ^int core-size ^int max-size ^long keep-alive-time
   TimeUnit/MILLISECONDS
   (ArrayBlockingQueue. ^int queue-size)
   ^ThreadFactory (thread-factory ^String name true nil)))



(defn fixed-thread-pool
  [name size & {:keys [queue-size] :as opts}]
  (thread-pool
   (assoc opts
          :name name
          :core-size size
          :max-size  size)))



(defn async-execute-with-pool
  "It returns a promise which will deliver tuple of 3 values where the
  first value is a the result of the execution of the thunk. The
  second value is `nil` when the execution is successful or `:error`
  if the thunk throws an exception, and `:queue-full` when the thread
  pool can't accent any more tasks."
  [^ExecutorService pool thunk]
  (let [value (promise)]
    (try
      (.execute
       pool
       (fn []
         (try
           (deliver value [(thunk) nil nil])
           (catch Throwable x
             (deliver value [nil :error x])))))
      (catch RejectedExecutionException x
        (deliver value [nil :queue-full nil])))
    value))



(defn execute-with-pool
  "It returns a promise which will deliver tuple of 3 values where the
  first value is a the result of the execution of the thunk. The
  second value is `nil` when the execution is successful or `:error`
  if the thunk throws an exception,`:queue-full` when the thread
  pool can't accent any more tasks, and `:timeout` when a timeout
  was reached."
  [^ExecutorService pool timeout thunk]
  (let [value (async-execute-with-pool pool thunk)]
    (deref value timeout [nil :timeout nil])))
