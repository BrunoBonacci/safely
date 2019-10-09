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




(defn thread*
  "It creates a thread and run the give thunk in the new thread.
  It returns a promise of a result, it's similar to a future, but you
  can control the thread name, priority etc.  If `auto-start` is
  `true` then the thread is started after its creation; if `false`
  then instead of returning a promise with the result it return a
  function with no arguments which when called it starts the thread
  and return the promise. All exception are caught and returned as
  result.

  examples:

  ```
  ;; create and start a thread named `runner`
  (thread* {:name \"runner\"}
    (fn []
      (println \"Hi from runner!\")))
  ;;=> nil
  ```

  ```
  ;; create and start a thread named `runner` and get the result
  @(thread* {:name \"runner\"}
    (fn []
      (reduce + (range 1000))))
  ;;=> 499500
  ```

  ```
  ;; create and start a thread named `runner`
  (def t
    (thread* {:name \"runner\" :auto-start false}
      (fn []
        (reduce + (range 1000)))))
  ;; call the function to start the thread, deref for the result
  @(t)
  ;;=> 499500
  ```

  ```
  ;; exceptions are retuned as result
  @(thread* {:name \"bad-runner\"}
    (fn []
      (/ 1 0)))
  ;; it capture and returns the exception
  ;;=> java.lang.ArithmeticException(\"Divide by zero\")
  ```

  "
  [{:keys [name daemon priority auto-start]
    :or {name   "safely-custom-thread"
         daemon false
         auto-start true}}
   thunk]
  (when thunk
    (let [result (promise)
          thunk* (fn []
                   (try (deliver result (thunk))
                        (catch Throwable x
                          (deliver result x))))
          thread   (Thread. ^Runnable thunk*)
          runner (fn [] (.start ^Thread thread) result)]

      (when name     (.setName thread name))
      (when daemon   (.setDaemon thread daemon))
      (when priority (.setPriority thread priority))

      (if auto-start
        (runner)
        runner))))



(defmacro thread
  {:doc "It creates a thread and run the give thunk in the new thread.
  It returns a promise of a result, it's similar to a future, but you
  can control the thread name, priority etc.  If `auto-start` is
  `true` then the thread is started after its creation; if `false`
  then instead of returning a promise with the result it return a
  function with no arguments which when called it starts the thread
  and return the promise. All exception are caught and returned as
  result.

  examples:

  ```
  ;; create and start a thread named `runner`
  (thread \"runner\"
      (println \"Hi from runner!\"))
  ;;=> nil
  ```

  ```
  ;; create and start a thread named `runner` and get the result
  @(thread {:name \"runner\"}
      (reduce + (range 1000)))
  ;;=> 499500
  ```

  ```
  ;; create and start a thread named `runner`
  (def t
    (thread {:name \"runner\" :auto-start false}
        (reduce + (range 1000))))
  ;; call the function to start the thread, deref for the result
  @(t)
  ;;=> 499500
  ```

  ```
  ;; exceptions are retuned as result
  @(thread \"bad-runner\"
      (/ 1 0))
  ;; it captures and returns the exception
  ;;=> java.lang.ArithmeticException(\"Divide by zero\")
  ```

  "
   :arglists '([& body]
               [name & body]
               [{:keys [name daemon priority auto-start]} & body])
   :style/indent 1}
  [& args]
  (let [df (cond
             (string? (first args)) {:name (first args)}
             (map? (first args)) (first args))
        thunk (if (nil? df) args (rest args))]
    `(thread* ~df (fn [] ~@thunk))))
