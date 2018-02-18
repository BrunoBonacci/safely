(ns safely.thread-pool
  (:import [java.util.concurrent
            ThreadPoolExecutor TimeUnit Callable Future
            ThreadFactory ArrayBlockingQueue
            ExecutorService RejectedExecutionException]))


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
  (try
    (.submit
     pool
     ^Callable
     (fn []
       (try
         [(thunk) nil nil]
         (catch Throwable x
           [nil :error x]))))
    (catch RejectedExecutionException x
      (deliver (promise) [nil :queue-full nil]))))



(defn cancel
  "Attempts to cancel execution of this task. This attempt will fail if
  the task has already completed, has already been cancelled, or could
  not be cancelled for some other reason. If successful, and this task
  has not started when cancel is called, this task should never
  run. If the task has already started, then the `:force true`
  parameter determines whether the thread executing this task should
  be interrupted in an attempt to stop the task.

  Returns `false` if the task could not be cancelled, typically
  because it has already completed normally; `true` if the
  cancellation was requested and `nil` if the task was not
  cancellable."
  [task & {:keys [force] :or {force false}}]
  (when (instance? Future task)
    (.cancel ^Future task ^boolean force)))



(defn timeout-wait
  "It waits on a  async value up to a given timeout.
  If the timeout is elapsed and the value is not available yet, then
  then the task is cancelled according to the `cancel-strategy`.
  accepted values: `:never`, `:if-not-running` or `:always`"
  [value timeout cancel-strategy]
  (let [[_ e _ :as v] (deref value timeout [nil :timeout nil])]
    (when (and (= e :timeout)
           (or (= :if-not-running cancel-strategy)
              (= :always cancel-strategy)))
      (cancel value :force (= :always cancel-strategy)))
    v))



(defn execute-with-pool
  "It returns a promise which will deliver tuple of 3 values where the
  first value is a the result of the execution of the thunk. The
  second value is `nil` when the execution is successful or `:error`
  if the thunk throws an exception,`:queue-full` when the thread
  pool can't accent any more tasks, and `:timeout` when a timeout
  was reached."
  [^ExecutorService pool timeout thunk
   & {:keys [cancel-on-timeout] :or {cancel-on-timeout :always}}]
  (let [value (async-execute-with-pool pool thunk)]
    (timeout-wait value timeout cancel-on-timeout)))



(defn running-task-count
  "Returns the approximate count of in flight tasks"
  [^ThreadPoolExecutor tp]
  (let [completed (.getCompletedTaskCount tp)
        scheduled (.getTaskCount tp)]
    (-  scheduled completed)))
