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
  (:require [safely.core :refer safely]))
```

## Exception handling

The macro `safely` will run the given code and in case an exception
arises it will follow the policy described after the `:on-error`
keyword.

### Return default value

This is the simplest of the policies. In case of an exception with the
given code a default value will returned.

```
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


## License

Copyright © 2015 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
