# CHANGELOG

## Version 0.7.0-SNAPSHOT (unreleased)
  * [NEW] Added `:rethrow` option to control which exception is thrown
    to the caller.
  * [NEW] Track logical failures (`:failed?`) with `:mulog/outcome :failed`
  * update dependencies
  * Add Clojure 1.12 as compilation target
  * [FIX #9] Use more intuitive exponential backoff


## Version 0.7.0-alpha3 (2021-01-14)
  * Small fix remove μ char to avoid loading issues.


## Version 0.7.0-alpha1 (2020-08-04)
  * [**NEW**] Migrated tracking to [***μ/log***](https://github.com/BrunoBonacci/mulog).
    [**BREAKING**] _The TrackIt! metering system is no longer supported._
    ***μ/log*** provides a much more comprehensive tracking system, and provides
    publisher for many of popular monitoring system.
    (no other changes are present in this version compared to `v0.5.0`)


## Version 0.5.0 (released: 2020-03-01)

  * [**NEW**] Introduced **circuit-breaker** functionality
  * [**BREAKING**] Metrics format: with the introduction of the
    *circuit-breaker* function I've restructured the metrics names and
    added new metrics, therefore if you rely on the old metrics name
    structure you might need to update your alerts. See the
    [tracking](./doc/tracking.md) page.
  * [**CHANGED**] Default retry policy changed from
    `[:random-exp-backoff :base 3000 :+/- 0.50]` to
    `[:random-exp-backoff :base 300 :+/- 0.50 :max 60000]` to
    accommodate wider type of use cases with the default case.
  * [**DEPRECATION**] From `v0.5.0-alpha7`, the `:max-retry` option is
    *deprecated* in favour of `:max-retries`. The old version will
    still work with the same behaviour, but display a warning message
    at compile time.  It will be removed in future releases.
