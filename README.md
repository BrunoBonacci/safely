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

In some cases by retying an operation you can get a successful outcome.
For example operations which involve network requests might time out
of fail for transitory network "glitches".
Typically, before giving up, you want to retry some operations.

For example, let's assume you wish to retrieve the list active users
from a corporate webservice and you want to account for transitory
failure, you could retry the operation a number of times before giving
up.

The code could look like as follow:

```Clojure
;; Automatic retry
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retry 3)
```

*In this case `:max-retry 3` means that there can be a maximum of 4
attempts* Between attempts the thread will be sleeping for a random
amount of time.  We will discuss retry delays later on.

If the first attempt succeed, then the result of the web request is
returned, however if an error arises then `safely` will retry until it
is successful or the `:max-retry` is reached.

At the point the `:max-retry` is reached, if a `:default` value has
been provided then it will be returned, otherwise the exception will
be thrown.


```Clojure
;; Automatic retry with default value
(safely
  (http/get "http://user.service.local/users?active=true")
  :on-error
  :max-retry 3
  :default {:accounts [] :status "BUSY"})
```



## License

Copyright © 2015 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
