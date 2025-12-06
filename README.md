### SnowflakeId Generator

- Nowadays, system design questions are part of all the interview process. I thought I will try to implement one of the problems - a unique id generator service and along the way learn some System Design.
- This repo contains an implementation of generating Snowflake IDs. 
- [Snowflake](https://blog.x.com/engineering/en_us/a/2010/announcing-snowflake) is a method to generate unique ids which was designed by twitter.

### Design
- At its root a Snowflake ID consists of 64 bits, and is _sortable_ by time
- The key parts of that 64 bits are:
  - 1st bit is always 0
  - Next 41 bits are made up by the timestamp which is the milliseconds since a custom epoch.
  - Next 5 bits are Datacenter ID
  - Next 5 bits are Machine ID
  - Last 12 bits are sequence number which is incremented by 1 and reset to 0 every millisecond.
- With 41 bits we can have `2^41` milliseconds, which gives us ~69 years
- In this implementation, Datacenter & Machine IDs are combined to 10 bits and are called Node Id, which is the node generating those Ids. Which gives us `2^10 - 1024` nodes
- The last 12 bits shows that within a millisecond we can generate `2^12 - 4096` unique ids.
- We are using MySQL DB for providing node ids which will be generating SnowflakeIds.

### Preparation

- Ensure that Mysql, Java and Gradle are installed on your system.
- Execute `make run` to create a database `snowflake_db` and ensure the application starts up.
- Run `mysql -u root < sql/access.sql` to create a snowflake user with password.
- In macos add the following in `~/.my.cnf` so that other computers can connect to your local mysql
```toml
[mysql]
bind-address = 0.0.0.0
```
- This is so that multiple computers on your network can work as a distributed id generator

### Quick Start

- Once the setup is complete run `make run` to boot up the application
- `curl -s http://localhost:8080/api/v1/snowflake/id` to get a single id
- `curl -s http://localhost:8080/api/v1/snowflake/batch?count=20 | jq` to get a batch of 20 ids and pretty print it
- `curl -s http://localhost:8080/api/v1/snowflake/652024909219758082/parse | jq` to see the individual parts of an id

### Load testing & Optimization

- We did a load test using `wrk` with the command `wrk -t12 -c400 -d30s --latency http://localhost:8080/api/v1/snowflake/next` and below are the results:
```
Running 30s test @ http://localhost:8080/api/v1/snowflake/next
  12 threads and 400 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     9.34ms   11.07ms 294.45ms   87.83%
    Req/Sec     4.69k   533.75     6.01k    82.53%
  Latency Distribution
     50%    5.10ms
     75%   10.36ms
     90%   22.84ms
     99%   47.69ms
  1677267 requests in 30.04s, 299.42MB read
  Non-2xx or 3xx responses: 1
Requests/sec:  55843.62
Transfer/sec:      9.97MB
```

- When I ran the load test and added some logs I saw "CAS Failed" logs very frequently. Which made me think that a lot of the times Ids which were generated had to be thrown because of contention.
- I asked the AI if there is something we can do to resolve it. It mentioned to use Lock strategy instead of CAS.
- Reason was that CAS is sort of like Optimistic Locking. Under high load CAS creates a "spin loop" where 
  - 100 threads try to get an ID at the same time.
  - All 100 reads the same `oldState`
  - 1 thread succeeds in updating it.
  - 99 threads fail, and loop to try again. This wastes CPU cycles calculating IDs that get thrown away.
- So, we should use Pessimistic Locking because the critical section is extremely fast - which is just bitwisse maths
- When critical section is this short, locking is _often faster than CAS under high load_. Reason being that a lock queues the threads so they execute one by one, rather than making them spin and fight each other.
- It added a Benchmark for testing both strategy and after running the Benchmark below are the results.

```
Benchmark                             Mode  Cnt     Score     Error   Units
SnowflakeBenchmark.testCasStrategy   thrpt    5  2910.920 ± 203.849  ops/ms
SnowflakeBenchmark.testLockStrategy  thrpt    5  4095.673 ±   8.251  ops/ms
```
- Clearly, lockStrategy is running at the near theoretical limit of ID generation.
- I reran the load test and below are the results:
```
Running 30s test @ http://localhost:8080/api/v1/snowflake/next
  12 threads and 400 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     8.58ms   14.45ms 453.65ms   95.76%
    Req/Sec     4.75k   526.51     5.73k    93.00%
  Latency Distribution
     50%    6.23ms
     75%    7.20ms
     90%   12.53ms
     99%   46.28ms
  1699668 requests in 30.04s, 303.42MB read
Requests/sec:  56571.14
Transfer/sec:     10.10MB
```
- Throughput increased by approx 728 req/sec
- To increase the throughput I used `spring.threads.virtual.enabled=true` property also, but it didn't increase.
- I also tried to start with the min heap size of 1GB and Max 2GB so that GC occurs less frequently, but that also didn't improve the throughput.

### Tasks Remaining

- Add Swagger API
- Add Junit Tests and Jacoco coverage
- Add checkstyle.xml, pmd-rules, spotbugs
- Add quality-metric-rules.json and quality-config.yaml
- Add Dockerfile
- Add metrics - prometheus / grafana
- Add docker-compose
- Add GitHub workflows
- Deploy on Digital Ocean - using ec2 like server
- Deploy on AWS - Using Fargate
- Try for Kubernetes deployment too 
- Add Nginx and Load balancing
- Add Load Testing stats
- Export logs to Splunk like service
- Add OpenTelemetry for Distributed Tracing
- Add Authorization and Authentication
- Dependency vulnerability scanning : OWASP Dependency-Check / Snyk / Trivy
- Add HTTPS
- Health Checks and Liveness probes
- Retry / Circuit Breaker / Bulkhead (Resilience4j)
- TestContainers - Spin up DB, Redis, Kafka for integration tests

### Remarks

- I plan on using this service in another common system design problem - Tiny URL generator.
