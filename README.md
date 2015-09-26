# safely

Safely it's a Clojure library for handling exception in code executions.
The library offers out of the box:

* declarative exception handling
* automatic retry policies
* randomized retries
* attenuation of self-emergent behaviour is distributed systems


## Usage

Add the dependency into your `project.clj`.

```
[com.brunobonacci/safely "0.1.0-SNAPSHOT"]
```

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
cycle.  Netflix described a similar situation in their tech blog (link
required), and they stress the importance of randomization in retry
strategy.

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

Additionally you can specify a maximum amount of time which beyond
which you want to wait for a similar amount of time.

```Clojure
;; Automatic retry with random-range
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

## License

Copyright © 2015 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
