# Safely tracking.

`safely` has a comprehensive metering system.  Internally it uses a
library called [TRACKit!](https://github.com/samsara/trackit) which in
turns it is a wrapper for the popular [Dropwizard's
Metrics](https://github.com/dropwizard/metrics) library.

Out of the box by just adding the `:track-as` option in a safely block
you get instrumentation metrics about how many times the safely block
is executed, at what rate (calls per second), and various percentiles
about the execution times.

For example let's take a look at the following code snippet:

``` clojure
(safely                                          \
                                                 |
  ;; an external call                 \          |
  (users/load-user :id "d548a66b")    | Inner    |
                                      /          | Outer
  :on-error                                      |
  :max-retry 5                                   |
  :default   nil                                 |
  :track-as  "myapp.mymodule.loadUser")          /
```

The `users/load-user` call is in a safely block, and we have added the
`:track-as "myapp.mymodule.loadUser"` option. In this case `safely`
will automatically instrument and push metrics based on the following
groups:

```
<track-as>.inner
<track-as>.outer
<track-as>.inner_errors
<track-as>.outer_errors
```

The `.inner` group refers call container inside the block, in our
example it is just the `users/load-user` call. While the `.outer`
refers to the entire `safely` block as perceived by the caller.

For example here are the metrics which will be pushed just by adding a
single `:track-as` line.

```
myapp.mymodule.loadUser.inner
             count = 41791                  >- execution count
         mean rate = 182.41 calls/second    \
     1-minute rate = 186.48 calls/second    |_ execution rate
     5-minute rate = 100.60 calls/second    |
    15-minute rate = 45.20 calls/second     /
               min = 32.37 milliseconds     \
               max = 117.19 milliseconds    |
              mean = 42.04 milliseconds     |
            stddev = 7.74 milliseconds      |
            median = 40.45 milliseconds     |_ execution time
              75% <= 43.39 milliseconds     |
              95% <= 53.48 milliseconds     |
              98% <= 62.67 milliseconds     |
              99% <= 74.44 milliseconds     |
            99.9% <= 117.19 milliseconds    /

myapp.mymodule.loadUser.outer
             count = 41791
         mean rate = 182.26 calls/second
     1-minute rate = 186.34 calls/second
     5-minute rate = 97.71 calls/second
    15-minute rate = 40.50 calls/second
               min = 32.39 milliseconds
               max = 79.93 milliseconds
              mean = 41.18 milliseconds
            stddev = 5.97 milliseconds
            median = 39.84 milliseconds
              75% <= 42.67 milliseconds
              95% <= 51.92 milliseconds
              98% <= 60.91 milliseconds
              99% <= 69.67 milliseconds
            99.9% <= 79.93 milliseconds

myapp.mymodule.loadUser.inner_errors
             count = 33                     >- errors count
         mean rate = 0.14 events/second     \
     1-minute rate = 0.16 events/second     |_ errors rate
     5-minute rate = 2.98 events/second     |
    15-minute rate = 4.86 events/second     /

myapp.mymodule.loadUser.outer_errors
             count = 0
         mean rate = 0.0 events/second
     1-minute rate = 0.0 events/second
     5-minute rate = 0.0 events/second
    15-minute rate = 0.0 events/second
```

All these metrics can be published to a variety of monitoring systems.
For more information about reporting metrics please refer to
[TRACKit!](https://github.com/samsara/trackit) documentation.

If you are using a *circuit-breaker* with `safely` the following
additional metrics are published.

For example let's take a look at the following code snippet:

``` clojure
(safely

  ;; an external call
  (users/load-user :id "d548a66b")

  :on-error
  :max-retry 5
  :default   nil
  :track-as  "myapp.mymodule.loadUser"
  :circuit-breaker :userLoad)
```

With the `:circuit-breaker :userLoad`, in addition to the previous
metrics, the following metrics will be published as well:

```
<track-as>.circuit_breaker.<cb-name>.errors.execution
<track-as>.circuit_breaker.<cb-name>.errors.queue_full
<track-as>.circuit_breaker.<cb-name>.errors.timeout
<track-as>.circuit_breaker.<cb-name>.errors.circuit_open
```

With the count and rate of the particular error type, for example:
```
myapp.mymodule.loadUser.circuit_breaker.userLoad.errors.queue_full
             count = 0
         mean rate = 0.0 events/second
     1-minute rate = 0.0 events/second
     5-minute rate = 0.0 events/second
    15-minute rate = 0.0 events/second
```
