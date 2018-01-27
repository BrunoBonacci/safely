# etl-load

This is a sample program that load a large file with millions of
records into a dynamo table. This example shows the use of `safely` in
a ETL context where your DB might be temporarily unavailable or might
throttle your requests based on usage quota limits. `safely` is
especially good in this case as it allows you to retry the failing
operation with a simple declaration.  The exponential backoff will
ensure that you always fully utilise the resources available (write
throughput) without swamping the system, and it will efficiently adapt
to changing conditions like an increase or decrease of capacity.

## Usage

```
You can set the following environment variables to change default settings:

  export AWS_ACCESS_KEY_ID='xxx'
  export AWS_SECRET_ACCESS_KEY='yyy'
    - set your API keys when outiside AWS, or use instance profiles instead.

  export AWS_REGION=eu-west-1
    - to set the AWS region to use

  export DYNAMO_TABLE=test-safely-etl-load

  export READ_CAPACITY=10
    - to set the DynamoDB initial read capacity (used only on creation)

  export WRITE_CAPACITY=500
    - to set the DynamoDB initial write capacity (used only on creation)

Usage:

  > lein run gen-file  "/tmp/file.json" <nrecs>
      To generate a sample data-file. <nrecs> is the number of records
      to generate, default: 1000000 (1M).

  > lein run load-file "/tmp/file.json"
      To load the file into dynamodb

```

## License

Copyright © 2015-2018 Bruno Bonacci

Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
