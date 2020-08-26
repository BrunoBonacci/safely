(ns safely.tracking-test
  (:require [midje.sweet :refer :all]
            [safely.core :refer :all]
            [safely.test-utils :refer :all]
            [safely.mulog-test-publisher :as tp]
            [com.brunobonacci.mulog :as u]))



;;
;; The use of `*result*` in these test is rather unorthodox but a
;; great simplification in terms of testing and debugging.  I similar
;; result can be achieved with a let binding, but it is much harder to
;; debug.
;;



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                   ----==| D I R E C T - C A L L |==----                    ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(fact "tracking: direct call"

  (fact "successful"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (safely
          (+ 1 1)
          :on-error
          :track-as :test
          :default 0)))

    ;; two trace events, one outer and inner
    (count *result*) => 2

    ;; checking general structure
    *result* =>
    (just [(contains
             {:mulog/duration integer?,
              :mulog/event-name :test
              :mulog/namespace "safely.tracking-test",
              :mulog/outcome :ok,
              :mulog/timestamp integer?,
              :safely/call-level :inner,
              :safely/call-site #"safely.tracking-test.*",
              :safely/call-type :direct,
              :safely/max-retries 0,
              :safely/attempt 0,})
           (contains
             {:mulog/event-name :test
              :mulog/namespace "safely.tracking-test",
              :mulog/outcome :ok,
              :mulog/parent-trace nil,
              :mulog/timestamp integer?,
              :safely/call-level :outer,
              :safely/call-site #"safely.tracking-test.*",
              :mulog/duration integer?
              })])

    ;; two trace-ids, one for each event
    (->> *result* (map :mulog/trace-id) set count) => 2

    ;; same root trace
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; the parent id matches the outer call
    (->> *result* (map :mulog/parent-trace) first)
    => (->> *result* (filter #(= (:safely/call-level %) :outer))
         (map :mulog/root-trace) last)
    )



  (fact "failure and retries"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (tp/ignore
          (sleepless
            (safely
              (/ 1 0)
              :on-error
              :track-as :test
              :log-stacktrace false
              :max-retries 3)))))

    ;; 5 trace events, one outer, one first attempt (inner)
    ;; plus 3 retries
    (count *result*) => 5

    ;; no breaker
    (->> *result* (map :safely/circuit-breaker)) => [nil nil nil nil nil]

    ;; checking attempts number
    (->> *result* (map :safely/attempt)) => [0 1 2 3 nil]

    ;; set of events fired
    (->> *result* (map :safely/call-level)) => [:inner :inner :inner :inner :outer]

    ;; correct error state
    (->> *result* (map :mulog/outcome)) => [:error :error :error :error :error ]

    ;; are all exceptions
    (->> *result* (map :exception))
    => (just [exception? exception? exception? exception? exception?] )

    ;; root-trace should be the same for all
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; parent-trace should be 4 same + nil (the root has no parent)
    (->> *result* (map :mulog/parent-trace) set)
    => (contains #{nil (partial instance? com.brunobonacci.mulog.core.Flake)})

    (->> *result* (map :mulog/parent-trace) last) => nil

    )



  (fact "failure and retries and success"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (tp/ignore
          (sleepless
            (let [expr (crash-boom-bang!
                         #(boom)
                         #(boom)
                         (constantly :success))]
              (safely
                (expr)
                :on-error
                :track-as :test
                :log-stacktrace false
                :max-retries 3))))))

    ;; 5 trace events, one outer, one first attempt (inner)
    ;; plus 3 retries
    (count *result*) => 4

    ;; no breaker
    (->> *result* (map :safely/circuit-breaker)) => [nil nil nil nil]

    ;; checking attempts number
    (->> *result* (map :safely/attempt)) => [0 1 2 nil]

    ;; set of events fired
    (->> *result* (map :safely/call-level)) => [:inner :inner :inner :outer]

    ;; correct error state
    (->> *result* (map :mulog/outcome)) => [:error :error :ok :ok]

    ;; are all exceptions
    (->> *result* (map :exception))
    => (just [exception? exception? nil nil] )

    ;; root-trace should be the same for all
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; parent-trace should be 3 same + nil (the root has no parent)
    (->> *result* (map :mulog/parent-trace) set)
    => (contains #{nil (partial instance? com.brunobonacci.mulog.core.Flake)})

    (->> *result* (map :mulog/parent-trace) last) => nil

    )
  )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;               ----==| C I R C U I T - B R E A K E R |==----                ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(fact "tracking: circuit-breaker"

  (fact-with-test-pools "successful"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (safely
          (+ 1 1)
          :on-error
          :track-as :test
          :circuit-breaker :cb-test
          :default 0)))

    ;; two trace events, one outer and inner
    (count *result*) => 2

    ;; circuit breaker
    (->> *result* (map :safely/circuit-breaker)) => [:cb-test :cb-test]

    ;; checking general structure
    *result* =>
    (just [(contains
             {:mulog/duration integer?,
              :mulog/event-name :test
              :mulog/namespace "safely.tracking-test",
              :mulog/outcome :ok,
              :mulog/timestamp integer?,
              :safely/call-level :inner,
              :safely/call-site #"safely.tracking-test.*",
              :safely/call-type :circuit-breaker,
              :safely/max-retries 0,
              :safely/attempt 0
              :safely/circuit-breaker :cb-test
              :safely/timeout nil})
           (contains
             {:mulog/event-name :test
              :mulog/namespace "safely.tracking-test",
              :mulog/outcome :ok,
              :mulog/parent-trace nil,
              :mulog/timestamp integer?,
              :safely/call-level :outer,
              :safely/call-site #"safely.tracking-test.*",
              :mulog/duration integer?
              })])

    ;; two trace-ids, one for each event
    (->> *result* (map :mulog/trace-id) set count) => 2

    ;; same root trace
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; the parent id matches the outer call
    (->> *result* (map :mulog/parent-trace) first)
    => (->> *result* (filter #(= (:safely/call-level %) :outer))
         (map :mulog/root-trace) last)
    )



  (fact-with-test-pools "failure and retries"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (tp/ignore
          (sleepless
            (safely
              (/ 1 0)
              :on-error
              :track-as :test
              :circuit-breaker :cb-test2
              :log-stacktrace false
              :max-retries 3)))))

    ;; 5 trace events, one outer, one first attempt (inner)
    ;; plus 3 retries
    (count *result*) => 5

    ;; circuit breaker
    (->> *result* (map :safely/circuit-breaker)) => [:cb-test2 :cb-test2 :cb-test2 :cb-test2 :cb-test2]

    ;; checking attempts number
    (->> *result* (map :safely/attempt)) => [0 1 2 3 nil]

    ;; set of events fired
    (->> *result* (map :safely/call-level)) => [:inner :inner :inner :inner :outer]

    ;; correct error state
    (->> *result* (map :mulog/outcome)) => [:error :error :error :error :error ]

    ;; are all exceptions
    (->> *result* (map :exception))
    => (just [exception? exception? exception? exception? exception?] )

    ;; root-trace should be the same for all
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; parent-trace should be 4 same + nil (the root has no parent)
    (->> *result* (map :mulog/parent-trace) set)
    => (contains #{nil (partial instance? com.brunobonacci.mulog.core.Flake)})

    (->> *result* (map :mulog/parent-trace) last) => nil

    )



  (fact-with-test-pools "failure and retries and success"
    (def ^:dynamic *result*
      (tp/with-test-publisher
        (tp/ignore
          (sleepless
            (let [expr (crash-boom-bang!
                         #(boom)
                         #(boom)
                         (constantly :success))]
              (safely
                (expr)
                :on-error
                :track-as :test
                :circuit-breaker :cb-test3
                :log-stacktrace false
                :max-retries 3))))))

    ;; 5 trace events, one outer, one first attempt (inner)
    ;; plus 3 retries
    (count *result*) => 4

    ;; circuit breaker
    (->> *result* (map :safely/circuit-breaker)) => [:cb-test3 :cb-test3 :cb-test3 :cb-test3]

    ;; checking attempts number
    (->> *result* (map :safely/attempt)) => [0 1 2 nil]

    ;; set of events fired
    (->> *result* (map :safely/call-level)) => [:inner :inner :inner :outer]

    ;; correct error state
    (->> *result* (map :mulog/outcome)) => [:error :error :ok :ok]

    ;; are all exceptions
    (->> *result* (map :exception))
    => (just [exception? exception? nil nil] )

    ;; root-trace should be the same for all
    (->> *result* (map :mulog/root-trace) set count) => 1

    ;; parent-trace should be 3 same + nil (the root has no parent)
    (->> *result* (map :mulog/parent-trace) set)
    => (contains #{nil (partial instance? com.brunobonacci.mulog.core.Flake)})

    (->> *result* (map :mulog/parent-trace) last) => nil

    )
  )



(fact "tracking: track circuit breaker failures"

  (fact-with-test-pools "check if queue-full and circuit-open are tracked as outcome."

    ;; this will saturate the active thread
    ;; and saturate the queue
    (dotimes [i 2]
      (future
        (safely
          (Thread/sleep 5000)
          :on-error
          :log-stacktrace false
          :track-as :load-user
          :circuit-breaker :cd-test4
          :thread-pool-size 1
          :queue-size 1
          :default :not-found)))

    (def ^:dynamic *result*
      (tp/with-test-publisher
        (dotimes [i 5]
          (sleepless
            (safely
              :success
              :on-error
              :track-as :test
              :circuit-breaker :cd-test4
              :thread-pool-size 1
              :queue-size 1
              :log-stacktrace false
              :default :ignore)))))

    (count *result*) => 10

    ;; set of events fired
    (->> *result* (map :safely/call-level))
    => [:inner :outer :inner :outer :inner :outer :inner :outer :inner :outer]

    ;; correct error state
    (->> *result* (map :mulog/outcome)) =>
    [:error :ok :error :ok :error :ok :error :ok :error :ok]

    ;; correct circuit breaker state
    (->> *result* (map :safely/circuit-breaker-outcome) (filter identity))
    => [:queue-full :queue-full :queue-full :circuit-open :circuit-open]
    )

  )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;               ----==| T R A C K I N G   C O N F I G |==----                ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(fact "if `:track-as` isn't defined it should default to `:call-site`"
  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (+ 1 1)
        :on-error
        :default 0)))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  ;; checking general structure
  *result* =>
  (just [(contains
           {:mulog/duration integer?,
            :mulog/event-name #"safely.tracking-test.*",
            :mulog/namespace "safely.tracking-test",
            :mulog/outcome :ok,
            :mulog/timestamp integer?,
            :safely/call-level :inner,
            :safely/call-site #"safely.tracking-test.*",
            :safely/call-type :direct,
            :safely/max-retries 0,
            :safely/attempt 0,})
         (contains
           {:mulog/event-name #"safely.tracking-test.*",
            :mulog/namespace "safely.tracking-test",
            :mulog/outcome :ok,
            :mulog/parent-trace nil,
            :mulog/timestamp integer?,
            :safely/call-level :outer,
            :safely/call-site #"safely.tracking-test.*",
            :mulog/duration integer?
            })])

  )



(fact "if `:track-as` isn't defined it should default to `:call-site`,
       if `:call-site` isn't available then, no logging."

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely-fn
        #(+ 1 1)
        :default 0)))

  ;; two trace events, one outer and inner
  (count *result*) => 0

  )



(fact "if `:tracking` is disabled no logging and not tracking id pollution."

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (u/log :check-pollution)
        #(+ 1 1)
        :on-error
        :tracking :disabled
        :default 0)))

  ;; two trace events, one outer and inner
  (count *result*) => 1

  ;; trace IDs haven't been polluted
  (->> *result*
    first
    ((juxt :mulog/root-trace :mulog/parent-trace)))
  => [nil nil]

  )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;              ----==| T A G S   A N D   C A P T U R E |==----               ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(fact "`:tracking-tags` are propagated to the mulog events (success)"

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (+ 1 1)
        :on-error
        :default 0
        :tracking-tags [:tag1 "value1" :tag2 :val2])))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  (map #(select-keys % [:tag1 :tag2]) *result*)
  =>
  (just [(contains {:tag1 "value1" :tag2 :val2})
         (contains {:tag1 "value1" :tag2 :val2})])

  )



(fact "`:tracking-tags` are propagated to the mulog events (error)"

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (/ 1 0)
        :on-error
        :default 0
        :log-stacktrace false
        :tracking-tags [:tag1 "value1" :tag2 :val2])))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  (map #(select-keys % [:tag1 :tag2]) *result*)
  =>
  (just [(contains {:tag1 "value1" :tag2 :val2})
         (contains {:tag1 "value1" :tag2 :val2})])

  )



(fact "`:tracking-capture` is used to enrich the mulog events (success)"

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (+ 1 1)
        :on-error
        :default 0
        :tracking-capture (fn [v] {:value v}))))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  (map #(select-keys % [:value]) *result*)
  =>
  (just [(contains {:value 2})
         (contains {:value 2})])

  )



(fact "`:tracking-capture` is used to enrich the mulog events (error)"

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (/ 1 0)
        :on-error
        :default 0
        :log-stacktrace false
        :tracking-capture (fn [v] {:value v}))))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  (map #(select-keys % [:value]) *result*)
  =>
  (just [{}
         (contains {:value 0})])

  )



(fact "`:tracking-capture` is used to enrich the mulog events (capture error)"

  (def ^:dynamic *result*
    (tp/with-test-publisher
      (safely
        (+ 1 1)
        :on-error
        :default 0
        :tracking-capture (fn [v] (/ 1 0)))))

  ;; two trace events, one outer and inner
  (count *result*) => 2

  (map #(select-keys % [:mulog/capture]) *result*)
  =>
  [{:mulog/capture :error}
   {:mulog/capture :error}]

  )
