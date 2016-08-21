# safely

Safely it's a Clojure library for handling exception in code executions.
The library offers out of the box:

  * declarative exception handling
  * automatic retry policies
  * randomized retries
  * attenuation of self-emergent behaviour is distributed systems
  * sleepless-mode for testing
  * automatic and customizable logging of errors
  * automatic tracking of errors rate/count in monitoring tools

## Usage

Add the dependency into your `project.clj`.

```
[com.brunobonacci/safely "0.2.2"]
```

Current version: [![safely](https://img.shields.io/clojars/v/com.brunobonacci/safely.svg)](https://clojars.org/com.brunobonacci/safely)


Require the namespace:

```
(ns foo.bar
  (:require [safely.core :refer [safely]]))
```

## Exception handling

The macro `safely` will run the given code and in case an exception
arises it will follow the policy described after the `:on-error`
keyword.

### Return default value

This is the simplest of the policies. In case of an exception with the
given code a default value will returned.

```Clojure
(safely
 (/ 1 2)

 :on-error
 :default 1)
;;=> 1/2


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
  :max-retry 3)
```

*In this case `:max-retry 3` means that there can be a maximum of 4
attempts* in total. Between each attempts the thread will be sleeping
for a random amount of time.  We will discuss retry delays later on.

If the first attempt succeed, then the result of the web request is
returned, however if an error arises then `safely` will retry until
one of the following conditions is reached: either a the operation
executes successfully, or the `:max-retry` is reached.

At the point the `:max-retry` is reached, if a `:default` value has
been provided then it will be returned, otherwise the exception will
be thrown up the stack.


```Clojure
;; Automatic retry with default value
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retry 3
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
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retry 3
  :fix 3000)
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
  :max-retry 3
  :random-range :min 2000 :max 5000)
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
  :max-retry 3
  :random 3000 :+/- 0.50)
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
  :max-retry 3
  :random-exp-backoff :base  3000 :+/- 0.50)
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
             THIS IS NOT HOW SAFELY OPERATE

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
  :max-retry 3
  :random-exp-backoff :base  3000 :+/- 0.50 :max 240000)
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
  :max-retry 6
  :rand-cycle [1000 3000 5000 10000] :+/- 0.50)
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
  `:debug`, `:info`, `:warn`, `:error`, `:fatal`, `:report`
* `:log-errors` (`true`|`false`) whether or not the error must be
  logged. If you don't want to log exceptions in a particular block
  you can disable it with: `:log-errors false`

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

* `:track-as` "myproject.errors.mymodule.myaction"
  Will use the given string as name for the metric. Use names which
  will be clearly specifying the which part of your code is failing
  for example: "app.errors.db.writes" and
  "app.errors.services.account.fetchuser" clearly specify which action
  if currently failing. The tracking is done via Samsara/TrackIt!
  (see: https://github.com/samsara/trackit)

For example:
```Clojure
;; Automatic retry with random-range
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retry 3
  :random-range :min 2000 :max 5000
  :track-as "myapp.errors.services.users.fetch-active")
```

This will track the number of failures of while fetching the active users,
and will track the rate as well (how often it happens) which can be easily
used as metric in a monitoring system.


### Macro vs function

`safely` it's a Clojure macro which wraps your code with a try/catch and offers
a elegant declarative approach to the error management. However in many cases
macro can't be used easily for this reason we provide a function as well.

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
  :max-retry 3
  :random-range :min 2000 :max 5000)
```

This is the same example **but with the `safely-fn` instead**:

```Clojure
;; Automatic retry with random-range
(safely-fn
  (fn []
    (http/get "http://user.service.local/users?active=true"))

  :max-retry 3
  :random-range :min 2000 :max 5000)
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
  :max-retry 3
  :default "")
```

This one does the same number of retries but doesn't sleep and it
returns immediately (same code path, but no sleep).

```Clojure
;; This one does the same number of retries but doesn't sleep
(binding [*sleepless-mode* true]
  (safely
    (slurp "/not/existing/file")
    :on-error
    :max-retry 3
    :default ""))
```


## Development

CI status: [![CircleCI](https://circleci.com/gh/BrunoBonacci/safely.svg?style=svg)](https://circleci.com/gh/BrunoBonacci/safely)

## TODO

  * Add custom handlers support

## License

Copyright © 2015 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
