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

    (->> *result* (map :safely/attempt)) => [0 1 2 3 nil]

    (->> *result* (map :safely/call-level)) => [:inner :inner :inner :inner :outer]

    (->> *result* (map :mulog/outcome)) => [:error :error :error :error :error ]

    (->> *result* (map :exception))
    => (just [exception? exception? exception? exception? exception?] )
    )

  )
