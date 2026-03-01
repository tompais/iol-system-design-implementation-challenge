# /spring-optimize — Spring WebFlux Server Tuning

Guide for tuning a reactive WebFlux rate-limiter for production. Covers Netty worker threads,
Reactor scheduler discipline, JVM memory flags, and Actuator health probes.

---

## 1. Netty Worker Thread Count

**File:** `src/main/resources/application.yaml`

```yaml
spring:
  webflux:
    netty:
      worker-count: 4   # rule of thumb: 2× CPU cores
```

**Why:** Netty's event loop threads handle all I/O. The default is `max(2, cpu_cores)`.
For a t2.micro (1 vCPU) the default of 2 is already correct. On larger instances set `2 × cpu_cores`.

---

## 2. Reactor Scheduler Discipline

**Never block a Reactor thread** (event loop). The rate-limiter uses `AtomicReference` CAS — pure
CPU arithmetic with no blocking I/O — so it is safe on the event loop today.

If you ever introduce Redis / JDBC calls, wrap them:

```kotlin
// BAD: blocks event loop
val result = redisClient.get(key)  // blocking call

// GOOD: offload to boundedElastic
withContext(Dispatchers.IO) {
    redisClient.get(key)
}
```

Reactor's `boundedElastic` scheduler is designed for blocking I/O offloading.

---

## 3. JVM Flags for Netty on Constrained Hosts

Set via the `JAVA_OPTS` environment variable (in `compose.yaml` or EC2 launch script):

```bash
JAVA_OPTS="-XX:+UseZGC -Xmx256m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError"
```

| Flag | Reason |
|------|--------|
| `-XX:+UseZGC` | Low-latency GC — sub-millisecond pauses ideal for reactive workloads |
| `-Xmx256m` | Leaves room for Grafana LGTM stack on t2.micro (1 GB total) |
| `-XX:MaxDirectMemorySize=128m` | Netty uses off-heap direct byte buffers for network I/O; without a cap the JVM default is `-Xmx`, which double-counts heap |
| `-XX:+ExitOnOutOfMemoryError` | Crash-fast rather than limp — container orchestrator will restart |

---

## 4. Actuator Readiness Probe

**File:** `src/main/resources/application.yaml`

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: always
```

**Verification:**

```bash
./gradlew bootRun &
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP","components":{...}}
```

AWS EC2 / Docker health check:
```yaml
# compose.yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/readiness"]
  interval: 10s
  timeout: 5s
  retries: 3
```

---

## 5. OTel Sampling

Set `probability: 1.0` (sample every request) in dev/staging; lower to `0.1` in production to
reduce trace storage cost:

```yaml
spring:
  otel:
    tracing:
      sampling:
        probability: 1.0
```

---

## Verification Checklist

- [ ] `application.yaml` — `worker-count` set to `2 × cpu_cores`
- [ ] No blocking calls on Reactor/Netty threads (grep for `.block()` in non-test code)
- [ ] `JAVA_OPTS` set in `compose.yaml` / EC2 launch script
- [ ] `curl localhost:8080/actuator/health` → `{"status":"UP"}`
- [ ] `curl localhost:8080/actuator/health/readiness` → `{"status":"UP"}`
