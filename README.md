# safely
[![Clojars Project](https://img.shields.io/clojars/v/com.brunobonacci/safely.svg)](https://clojars.org/com.brunobonacci/safely) ![CircleCi](https://img.shields.io/circleci/project/BrunoBonacci/safely.svg) ![last-commit](https://img.shields.io/github/last-commit/BrunoBonacci/safely.svg) [![Dependencies Status](https://jarkeeper.com/BrunoBonacci/safely/status.svg)](https://jarkeeper.com/BrunoBonacci/safely)

Safely it's a Clojure's circuit-breaker library for handling retries
in code executions in a elegant declarative way.
The library offers out of the box:

  * declarative exception handling
  * declarative *circuit breaker* (in pure Clojure)
  * automatic retry policies
  * randomized delays retries
  * attenuation of self-emergent behaviour is distributed systems
  * sleepless-mode for testing
  * automatic and customizable logging of errors
  * automatic tracking of errors rate/count in monitoring tools
  * automatic tracking of common performance metrics

## Usage

Add the dependency into your `project.clj`.

``` clojure
;; stable version with circuit breaker
[com.brunobonacci/safely "0.5.0-alpha7"]
;; (no circuit-breaker)
[com.brunobonacci/safely "0.3.0"]
```

Current version: [![safely](https://img.shields.io/clojars/v/com.brunobonacci/safely.svg)](https://clojars.org/com.brunobonacci/safely)


Require the namespace:

``` clojure
(ns foo.bar
  (:require [safely.core :refer [safely]]))
```

Then, make a call to a remote system:

``` clojure
;; wrap your critical calls
;; to external systems (api, db, etc)
;; into a `safely` block, and define
;; what to do in case of failures.

(safely
  (api-call "other-system")

  :on-error
  :max-retries 5
  :default   {:some :value})
```

This is a quick ref-card of all possible configurable options:

``` clojure

;;
;; all in one example
;;

(safely

 ;; code to execute
 (do (comment run something which can potentially blow))

 ;; exception handling
 :on-error

 ;; upon error return a default value
 :default "some value"

 ;; retry a number of times before
 ;; to give up or return the default value
 ;; use :forever for unlimited retries.
 :max-retries 5

 ;; between retries wait a fix amount of time (not recommended)
 :retry-delay [:fix 3000] ;; 3s in millis

 ;; or wait a uniform random range between :min and :max
 :retry-delay [:random-range :min 1000 :max 3000]

 ;; or wait a random amount of time with +/- a random variation
 :retry-delay [:random 3000 :+/- 0.35]

 ;; or wait an exponential amount of time with a random variation
 :retry-delay [:random-exp-backoff :base 3000 :+/- 0.50]
 :retry-delay [:random-exp-backoff :base 3000 :+/- 0.35 :max 25000]

 ;; or wait a given list of times with a random variation
 :retry-delay [:rand-cycle [50 100 250 700 1250 2500] :+/- 0.50]

 ;; you can provide a predicate function which determine
 ;; which class of errors are retryable. Just write a
 ;; function which takes an exception and return something
 ;; truthy or falsey.
 :retryable-error? #(not (#{ArithmeticException NullPointerException} (type %)))

 ;; you can provide a predicate function which determine
 ;; if the output of the body should be considered as a filed response
 ;; this can be useful when using safely with APIs which have a return
 ;; status for errors instead of exceptions. Two good examples are HTTP
 ;; status codes and polling API, in which you wish to slow down the polling
 ;; when the result of the previous polling doesn't contain records.
 :failed? #(not (>= 200 (:status %) 299))

 ;; to activate the circuit breaker just give a name to the operation
 :circuit-breaker :operation-name

 ;; the following options are ONLY used in conjunction with
 ;; a circuit breaker

 ;; control the thread pool size for this operation
 :thread-pool-size  10

 ;; control the thread pool queue size for this operation
 :queue-size        5

 ;; the number of request's outcome to be sampled for analysis
 :sample-size       100

 ;; the number of milliseconds to wait before giving up
 :timeout           30000 ;; (millis, default wait forever)

 ;; What to do with the request when the timeout time is
 ;; elapsed. :never, :if-not-running or :always
 :cancel-on-timeout :always

 ;; stats are collected about the outcome of the operations
 ;; this parameter controls the number of 1-sec buckets
 ;; to control.
 :counters-buckets  10

 ;; the strategy used to trip the circuit open
 :circuit-breaker-strategy :failure-threshold

 ;; the threshold of failing requests after which the circuit trips
 ;; open. This is only used when
 ;; :circuit-breaker-strategy is :failure-threshold
 :failure-threshold 0.5

 ;; when the circuit breaker is tripped open, no requests will
 ;; be allowed for a given period.
 :grace-period      3000 ;; millis

 ;; the strategy to decide which requests to let through
 ;; for evaluation before closing the circuit again.
 :half-open-strategy :linear-ramp-up

 ;; the number of millis during which time an incrising number
 ;; of requests will be let through for evaluation purposes.
 :ramp-up-period    5000


 ;; General options.
 ;; customize your error message for logs
 :message "a custom error message"

 ;; set to false if you don't want to log errors
 :log-errors false

 ;; or choose the logging level
 :log-level :warn

 ;; to disable the stacktrace reporting in the logs
 :log-stacktrace false

 ;; and track the number of failure with the given metrics name
 :track-as "myproject.errors.mymodule.myaction"

 )

```

## Examples and Case studies

Here a collection of examples and case studies:

  * Use safely with AWS apis
    * [ETL-load job](./examples/etl-load/doc/etl-load-example.md) -
      See how the use of `safely` can greatly simplify your ETL jobs
      and make sure that you are fully utilising your database
      resources while being tolerant for transitory failures. It is
      also demonstrated that the exponential backoff exhibits great
      adaptive behaviours. The example is valid for Hadoop and Spark
      ETL jobs as well.


## Exception handling

The macro `safely` will run the given code and in case an exception
arises it will follow the policy described after the `:on-error`
keyword.

### Return default value

This is the simplest of the policies. In case of an exception with the
given code a default value will returned.

```Clojure
;; no error raised, so result is returned
(safely
 (/ 1 2)

 :on-error
 :default 1)
;;=> 1/2


;; an error is raised, but a default value is given
;; so the default value is returned
(safely
 ;; ArithmeticException Divide by zero
 (/ 1 0)

 :on-error
 :default 1)
;;=> 1
```

### Automatic retry

In some cases by retying a failed operation you can get a successful
outcome.  For example operations which involve network requests might
time out of fail for transitory network "glitches".  Typically, before
giving up, you want to retry some operations.

For example, let's assume you wish to retrieve the list active users
from a corporate RESTful webservice and you want to account for
transitory failures, you could retry the operation a number of times
before giving up.

The code could look like as follow:

```Clojure
;; Automatic retry
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3)
```

*In this case `:max-retries 3` means that there can be a maximum of 4
attempts* in total. Between each attempts the thread will be sleeping
for a random amount of time.  We will discuss retry delays later on.

If the first attempt succeed, then the result of the web request is
returned, however if an error arises then `safely` will retry until
one of the following conditions is reached: either a the operation
executes successfully, or the `:max-retries` is reached.

At the point the `:max-retries` is reached, if a `:default` value has
been provided then it will be returned, otherwise the exception will
be thrown up the stack.


```Clojure
;; Automatic retry with default value
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :default {:accounts [] :status "BUSY"})
```

In the previous case the HTTP GET operation may fail and it will be
automatically retried for a maximum of 3 times, after which, the
default value of `{:accounts [] :status "BUSY"}` is returned.

If the `:default` clause it is omitted the a
`clojure.lang.ExceptionInfo` will the thrown with the details of the
number of attempts and the original cause.


### Retry delays and randomization

#### Self-emergent Behaviour

In large distributed systems failures can produce strange behaviour
due to the fact that all participant act in the exact same way.
Consider the example of a service failure where all other services
which use the former detect the failure and decide to retry after the
exact same amount of time. When the system comes back to life it will
be flooded with retry requests from all the other services at the same
time. If the number of client service is big enough can cause the
service which is already struggling to die and reboot in a continuous
cycle.

> "Emergent behavior is that which cannot be predicted through analysis
> at any level simpler than that of the system as a whole. Emergent
> behavior, by definition, is what’s left after everything else has been
> explained" (Dyson and George 1997).

> "Emergent behavior is also been defined as the action of simple rules
> combining to produce complex results" (Rollings and Adams 2003)

In this paper
[Emergent Behavior in Systems of Systems]( http://faculty.nps.edu/thuynh/Conference%20Proceedings%20Papers/Paper_14_Emergent%20Behavior%20in%20Systems%20of%20Systems.pdf)
you can see more examples of emergent behaviour.


#### Retry policies

`safely` implements several randomization strategies to minimize
the appearance of these large scale issues.

All delay strategies are randomized by default, here is a list of those
we currently support.

The default configuration is: `[:random-exp-backoff :base 300 :+/- 0.50 :max 60000]`

* `:random-range` (min/max) - it define a random range between a fixed boundary
* `:random` (amount +/- random percentage) - It define an amount and
  percentage of variation (both sides + or -) from that base amount
* `:random-exp-backoff` - (**default strategy**) it define an amount
  of time between each attempt which grows exponentially at every
  subsequent attempt and it is randomized with a custom +/-
  percentage. Optionally you can specify a maximum amount of time
  beyond which it won't grow any more.
* `:rand-cycle` - if none of the above strategies suits your case you
  can specify your own list of delays between each attempts and a
  randomization factor. If the number of attempts goes beyond the
  listed values it will start from the first one again in a continuous
  cycle.
* `:fix` for special cases you can specify a fix amount of time
  between retry, however i do not recommend the use of this strategy.

Now we will show how each strategy works with code samples.


#### :fix

In this example `safely` will retry for a maximum of 3 times with a
delay 3 seconds (3000 milliseconds) exacatly. This strategy is
strongly discouraged in order to minimize self emergent behaviour.

```Clojure
;; Automatic retry with fix interval (NOT RECOMMENDED)
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:fix 3000])
```

#### :random-range

In this example `safely` will retry for a maximum of 3 times with a
delay of minimum 2 seconds (2000 milliseconds) and a maximum of 5
seconds (5000 milliseconds).

```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random-range :min 2000 :max 5000])
```

#### :random

In this example `safely` will retry for a maximum of 3 times with a
delay 3 seconds (3000 milliseconds) and plus or minus an amount **up
to** 50% of the base amount. This means that the waiting time could be
effectively anything between 1500 millis (3000 - 50%) and 4500 millis
(3000 + 50%).

```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random 3000 :+/- 0.50])
```

#### :random-exp-backoff

In this example `safely` will retry for a maximum of 3 times with a
exponential backoff delay of 3 seconds (3000 milliseconds) and plus or
minus random 50% of the base amount. This means that the first retry
will be ~3 sec (+/- random variation), the second retry will ~9 sec
(+/- random variation) etc.

```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random-exp-backoff :base  3000 :+/- 0.50])
```

**The Math gotchas:** The exponential backoff typically follows this
formula:

    delay = base-delay ^ retry-number +/- random-variation

for a typical exponential back off for 3 sec would be:

    retry:     1     2     3     4 ...
    formula:  3^1   3^2   3^3   3^4
    delay:     3     9    27     81 sec

however the base amount is specified in milliseconds (not in seconds)
which mathematically would be (this is not how `safely` implements the
backoff):

    retry:     1         2          3         4 ...
    formula: 3000^1   3000^2      3^3       3^4
    delay:   3000     9000000    27+E9     81+E12
              3s     2.5 hours  +20 years
             THIS IS NOT HOW SAFELY OPERATES

Which means the second retry will be after *2.5 hours* and the third
retry will be after *20 years*. I'm sure that none of your apps
wants to wait 20 years before retrying, therefore `safely` despite
requiring the time in milliseconds will try to adapt the exponential
backoff base to scale of the number.

So for example for a given base you have the number of
milliseconds of each subsequent retry:


| Base | Retry 1 | Retry 2 | Retry 3 | Retry 4 | Retry 5 |
|-----:|--------:|--------:|--------:|--------:|--------:|
|   50 |      50 |     250 |    1250 |    6250 |   31250 |
|  200 |     200 |     400 |     800 |    1600 |    3200 |
| 2000 |    2000 |    4000 |    8000 |   16000 |   32000 |
| 3000 |    3000 |    9000 |   27000 |   81000 |  243000 |


The algorithm takes base and it compute the size of base (log10 base)
and it strips down the least relevant digits before the power is
applied.  This ensures that the exponential back off maintains
practical timing while still increasing the delay of every retry.

The following formula explains better how it works :

![formula](/doc/images/retry-formula.png)


If you wish to check the sequence for a given base you can try on the
REPL as follow:

```Clojure
(require 'safely.core)
(take 10 (#'safely.core/exponential-seq 2000))
;;=> (2000 4000 8000 16000 32000 64000 128000 256000 512000 1024000)
```

**The randomization is applied after the exponential value has been
  calculated**

#### :random-exp-backoff (with :max)

Additionally you can specify a maximum amount of time which beyond
which you want to wait for a similar amount of time.

```Clojure
;; Automatic retry with random-range with a max delay
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random-exp-backoff :base  3000 :+/- 0.50 :max 240000])
```

The above example set a maximum delay of **4 minutes** (240000 millis)
beyond which time `safely` won't backoff exponentially any more, but
it will remain constant.

Example for the effect of `:max 240000`

```Clojure
(require 'safely.core)
;; without :max
(take 10 (#'safely.core/exponential-seq 3000))
;;=> (3000 9000 27000 81000 243000 729000 2187000 6561000 19683000 59049000)

;; with :max 240000
(take 10 (#'safely.core/exponential-seq 3000 240000))
;;=> (3000 9000 27000 81000 240000 240000 240000 240000 240000 240000)
```

#### :rand-cycle

If you don't like the exponential backoff, then you can specify a
sequence of expected delays between each retry. `safely` will use these
times (in milliseconds) and add randomization to compute the amount of
delay between each retry. Once last delay in the sequence is reached
`safely` will cycle back to the first number and repeat the sequence.

```Clojure
;; Automatic retry with random list of delays
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 6
  :retry-delay [:rand-cycle [1000 3000 5000 10000] :+/- 0.50])
```

In the above example I've specified the desired waiting time (with
variation) of **1s, 3s, 5s and 10s**, I've also specified that I would
like `safely` to retry **6 times**, but only 4 wait times were
specified. Safely will cycle back from the beginning of the sequence
producing effective waiting times of:

    retry:     1     2     3     4      5     6
    delay:   1000  3000  5000  10000  1000  3000
             |---------------------|  |---------...
       cycling back to the beginning of the sequence

In this way you can specify your custom values which better suits
your particular situation.


### Errors logging

One common mistake is to have empty `catch` block. The exception in this case
it is swallowed by the program without leaving any trace. There are very few
occasion when this is a good idea, in most of the cases it is recommended to
at least log the exception in a logging system.
`safely` by default logs the exception with `timbre`. There are a few configurable
option which you can leverage to make message more suitable for your situation.

We have:

* `:message` to customize the log message and make it more meaningful
  with information which pertain the action you were trying to
  achieve.
* `:log-level` the level to use while logging the exception. The
  default value is `:warn`, other possible values are: `:trace`,
  `:debug`, `:info`, `:warn`, `:error` and `:fatal`
* `:log-errors` (`true`|`false`) whether or not the error must be
  logged. If you don't want to log exceptions in a particular block
  you can disable it with: `:log-errors false`
* `:log-stacktrace` (`true`|`false`) whether to report the full
  stacktrace of the exception or omit it completely. (default `true`)
* `:log-ns "logger.name"` To specify a logger name. Typically the
  name of a namespace. When using the macro it defaults to the current
  namespace, when using the function version it defaults to `safely.log`

For example this log the exception with the given message and a log
level of `:info`.

```Clojure
;; Customize logging
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :message "Error while fetching active users"
  :log-level :info)
```

In this case we disable the error logging for the given block.

```Clojure
;; Disable logging
(safely
  (Thread/sleep 3000)
  :on-error
  :log-errors false)
```

### Automatic errors tracking (monitoring)

If you have (and you should) a monitoring system which track application
metrics as well then you can track automatically how many times a
particular section protected by safely is running into errors.

To do so all you need to do is to give a name to the section you are
protecting with safely with:

* `:track-as "myproject.mymodule.myaction"`
  Will use the given string as name for the metric. Use names which
  will be clearly specifying the which part of your code is failing
  for example: "app.db.writes" and
  "app.services.account.fetchuser" clearly specify which action
  if currently failing. The tracking is done via Samsara/TrackIt!
  (see: https://github.com/samsara/trackit)

For example:

```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random-range :min 2000 :max 5000]
  :track-as "myapp.services.users.fetch-active"
  :circuit-breaker :fetch-active-users)
```

This will track a number of interesting metrics about this single
block and publish them to a variety of monitoring systems. For more
information you can see the [tracking](./doc/tracking.md) page.

### Circuit breaker.

The circuit breaker functionality (introduced in v0.5.0) was
popularised by [M. T. Nygard's book "Release
It!"](https://pragprog.com/book/mnee/release-it).  There are already a
good amount of open-source libraries which offer quite good
implementation of circuit-breakers as defined by Nygard. The most
popular it is [Hystrix](https://github.com/Netflix/Hystrix) from
Netflix.  However, Hystrix over the years became unnecessarily a huge
library.  `safely` offers an implementation of the same ideas in a
much simplified way and 100% Clojure (for JVM).

If you want to know more about the general idea behind the circuit
breaker I would recommend the book "Release It!" mentioned above. Here
I'm going to describe how `safely` implementation works.

Internally the circuit breaker is a state machine which looks like
this:

![circuit breaker state machine](/doc/images/circuit-breaker-sm.png)

The state machine is initiated with the `:closed` state. Like an
electrical circuit a _closed_ circuit it is a working circuit in which
the current can flow through.

#### **`:closed` state**

In this state the circuit breaker is allowing to pass all the
requests. So when a new request is issued, the circuit breaker will
retrieve the dedicated thread pool associated with this request type
and enqueue the new request. Once enqueued an available thread will
pick the request and process it. When the request is completed then
the circuit breaker will update its internal state capturing the
outcome of each request. In this case one of the following things can
happen:

  - **the request is successful**, then the result from the processing
    thread is returned to the caller.
  - **the request processing fails with an error**, in this case the
    error is propagated back to the caller and further retries could
    be made depending whether they are configured and within the
    limit. If the limit of retries in `:max-retries` is reached then the
    `:default` value is returned when provided or the error itself.
  - **the request times out**, if the request has configured
    `:timeout` and the processing isn't completed within this time, an
    exception is raised and it follows the same path and the
    processing error.
  - **all threads and busy and no more requests can be enqueued**, in
    this case the request is rejected (`:queue-full`) and the same
    error handling path or retries is used.  The number of threads and
    the queue size are configurable parameters.  More on how to size
    them properly later.

For any of the above outcomes the circuit breaker state machine updates
a counter. Only counters for the last few seconds are kept and they are
used by the state evaluation function to determine whether the circuit breaker
should be tripped and move to the next state.

Currently the following strategies are available to trip the circuit breaker:

  - **:failure-threshold** which it looks at the counters and trips
    the circuit open when a configurable threshold of failing requests
    is reached. It will wait until at least 3 requests have been
    processed before verifying the threshold. Very simple and
    effective.


#### **`:open` state**

If the state evaluation function decides to trip the circuit off
because too many errors occurred, then the circuit breaker state
machine goes into the `:open` state. In this state all incoming
requests are rejected immediately with a `:circuit-open` error and the
standard error path with retries is followed.

This is useful to immediately reduce the load into the target system.
The circuit stays open for a few seconds (according to
`:grace-period`) and then the circuit automatically transitions to the
`:half-open` state.

#### **`:half-open` state**

The purpose of this state is to assess whether the target system is
back to normal before closing the circuit back and allow all the
requests. So for this purpose the circuit breaker allows only a few
requests to pass and it checks their outcome. If the system keep
failing then the circuit goes back to the `:open` state, if the
requests and now successful and the issue seems to be resolved then
the circuit goes back to the `:closed` state.
The same evaluation function used to trip the circuit open is used
to evaluate whether now is back to normal.

During the `:half-open` state, only a part of the incoming requests
will be allowed. The number of the requests allowed depends on the
`:half-open-strategy`.

These are the currently supported strategies:

  - **`:linear-ramp-up`**, it will ramp up the number of requests
  allowed in the circuit breaker over time. The length of time is
  configurable via `:ramp-up-period`. The system will go back to
  closed only after the `:ramp-up-period` is elapsed, however if it
  detects failures during the ramp up it will preemptively open the
  circuit again.


#### How to use the circuit-breaker

To activate the circuit breaker function just add the `:circuit-breaker`
option if your `safely` options:

```Clojure
;; activating circuit breaker
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  ;; give a name to the circuit-breaker
  :circuit-breaker :fetch-active-users
  ;; optionally set a timeout for this operation (millis)
  :timeout 30000)
```

That's it!. `safely` in the background will create a thread pool named
`:fetch-active-users` which will be in charge of processing the
requests. You can use the circuit breaker in conjunction with all
other safely options such as retry strategies, log and tracing.

_**NOTE**: for every unique value passed to `:circuit-breaker` a
number of resources need to be created in the system, namely the
thread-pool and the circuit-breaker state machine. Therefore you must
ensure that the values passed to the `:circuit-breaker` options are
**not randomly generated or high cardinality** to avoid the risk of
running out of memory in your system. Best practice is to name the
circuit breaker after the operation that it is trying to accomplish._


#### Circuit breaker functions

##### `shutdown-pools`

For every named circuit breaker, `safely` will create its own
dedicated thread pool. If you wish to shutdown the pool
programmatically then you can call the `shutdown-pools` function
with a specific circuit breaker name or without parameters
to shut all of them down.

##### `circuit-breaker-info`

If you want to access the info stored in the state machine
for monitoring purposes then you can use the `circuit-breaker-info`
function with a circuit breaker name for the state regarding the
specific circuit breaker or without parameters for all.

#### How to size the thread pool

You might think that a thread pool of 10 is very small for your
system, and you might be tempted to increase this number by one order
of magnitude.  Although some times this is the correct thing to do,
most of the time it won't be. The defaults are already set for large
volume systems so most of you won't need to change the size of the
thread pool and/or the queue length.  However if you think you should
change these values for your system I would recommend to use the
[Litlle's Law](http://web.mit.edu/~sgraves/www/papers/Little's%20Law-Published.pdf)
(from Queueing Theory) to choose the correct size.

The _Little's Law_ says that the long term average number of items `L`
in your system is equal to the average arrival rate `λ` multiplied by
the long term average time `W` required to process that item, therefore:

![Little's Law](/doc/images/LittleLaw.png)

The interesting property about the _Little's Law_ is that it applies
to the whole system as well as its individual parts.  This means that
this law will apply to your system as a whole, meaning all the
instances of your system in the cluster, as well as the individual
instances. Moreover, if your single instance has two possible paths
with two different probabilities, it will apply to these sub-parts as
well with the parameters adjusted accordingly.

For example if you have a system which processes 5000 requests/second
as a whole, and you have 15 instances to serve these requests,
and each requests takes on average 25 milliseconds, then we can reason
as follow:

  * `λ = 5000 rq/s`
  * `W = 25 millis -> 0.025s`
  * then we can deduce that `L` for the whole system is going to be:
  * `L = λW -> 5000 rq/s * 0.025 s -> L = 125`
  * So it means that the whole system will have a average of 125
    concurrent requests when processing 5000 rq/s.
  * Since every instance follow the Little's law as well and
    since all the instances have typically the same probability
    to get a request (via a load balancer), then it is safe
    to assume that every instance will have the same share of traffic.
    Since we ha *15 instances* then we can say that:
  * `Li = L / 15 -> 125 / 15 -> Li = 8.34` where `Li` is the load of a
    single instance.

As you can see although your system as a whole processes a lot of
requests per seconds, the individual instance _concurrent load `Li`_
it will be within the range of the thread pool. If we size the thread
pool a bit larger to cope with requests bursts and we add a small
queue typically 30%-50% of the thread pool size we can ensure that
occasional hiccups and bursts of requests are handled properly without
causing the circuit breaker to trip over.

I hope this small guide helps you to correctly size your system.
Anyway, always use measurements (tracking, monitoring) to compute
the right size and verify you changes according to your assumptions
to see if the change had the effect you hoped.


### Macro vs function

`safely` it's a Clojure macro which wraps your code with a try/catch
and offers a elegant declarative approach to the error
management. However in many cases macro can't be used easily for this
reason we provide a function as well.

Everything you can do with the macro `safely` you can do with the
function `safely-fn` which takes a **thunk** (function with zero
arguments and the same options with `safely` takes after the
`:on-error` clause.

So for example this is the use of the macro you have seen so far:

```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retries 3
  :retry-delay [:random-range :min 2000 :max 5000])
```

This is the same example **but with the `safely-fn` instead**:

```Clojure
;; Automatic retry with random-range
(safely-fn
  (fn []
    (http/get "http://user.service.local/users?active=true"))

  :max-retries 3
  :retry-delay [:random-range :min 2000 :max 5000])
```

_Note the use of the **thunk** to wrap the code and the absence of the
 `:on-error` keyword._


### Testing and the `sleepless-mode`

If you are writing automated test but you don't want to wait then
you can enable the **sleepless-mode** in order to skip the waiting
times of the retry for example:

This might wait up to 40s before returning "".

```Clojure
;; this might wait up to 40s before returning ""
(safely
  (slurp "/not/existing/file")
  :on-error
  :max-retries 5
  :default "")
```

This one does the same number of retries but doesn't sleep and it
returns immediately (same code path, but no sleep).

```Clojure
;; This one does the same number of retries but doesn't sleep
(binding [safely.core/*sleepless-mode* true]
  (safely
    (slurp "/not/existing/file")
    :on-error
    :max-retries 5
    :default ""))
```


## Development

CI status: [![CircleCI](https://circleci.com/gh/BrunoBonacci/safely.svg?style=svg)](https://circleci.com/gh/BrunoBonacci/safely)

## License

Copyright © 2015-2018 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
