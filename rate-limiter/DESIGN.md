# Rate Limiter — Design Document

## Algorithm Choice: Token Bucket

**Chosen algorithm:** Token Bucket with lazy refill.

The two main candidates were Token Bucket and Sliding Window Counter. Token Bucket was chosen because it models real-world bursty traffic naturally — tokens accumulate during quiet periods and drain during bursts — which matches production API behavior. A client that is quiet for 2 seconds earns 2× capacity tokens (capped), allowing a legitimate burst, rather than being penalized for the previous window's inactivity. Sliding Window Counter requires a ring buffer or sorted set of timestamps per key and needs a background sweep for cleanup, adding operational complexity with no benefit for this use case. Fixed Window Counter has a well-known boundary exploit (2× burst at window edges) that makes it unsuitable for rate limiting.

**Lazy refill vs. scheduled background thread:** Refill is computed on each `tryConsume()` call by comparing the current timestamp to the `lastRefillAt` timestamp stored in `BucketState`. `earned = min(refillRatePerSecond × elapsedMs, capacity × 1000)` (units cancel: tokens/sec × ms = milliTokens). The cap on `earned` prevents Long overflow and unexpectedly huge balances if the clock jumps far forward. `elapsedMs` is itself guarded with `maxOf(0L, ...)` so a backward clock step never produces negative earned tokens. This eliminates the need for a background scheduler thread entirely — no goroutines, no timers, no coordination overhead, just arithmetic on each request. The trade-off is that a burst of concurrent requests all compute the same refill amount simultaneously; this is correct because the CAS loop serialises which write wins.

---

## Thread-Safety Model

**The race condition:** Multiple HTTP threads may hit the same rate-limit key simultaneously (e.g., a mobile app sending parallel API requests with a shared user key). Without atomicity, the TOCTOU (Time-Of-Check-Time-Of-Use) race occurs: Thread A reads `milliTokens=1000`, Thread B reads `milliTokens=1000`, both decide "allowed", both subtract 1000 — the same token is spent twice and both requests pass when only one should.

**Solution:** `AtomicReference<BucketState>` + Compare-And-Swap (CAS) loop in `TokenBucketRateLimiter.tryConsume`. The loop reads the current state, computes the refilled state, and calls `ref.compareAndSet(current, next)`. The JVM atomically writes `next` only if the reference still points to `current`. If another thread won the race and mutated the reference first, `compareAndSet` returns `false` and the loop retries with a fresh `ref.get()`. This is lock-free — no `synchronized` blocks, no `ReentrantLock`, no thread blocking.

`InMemoryBucketStore` uses `ConcurrentHashMap.computeIfAbsent` for atomic key creation, ensuring exactly one `AtomicReference<BucketState>` is shared per key even under concurrent first-access. The `BucketStore` port is defined to return `AtomicReference` directly — callers must share the same reference object, not copies of it.

**Validation:** `TokenBucketConcurrencyTest` runs 100 threads simultaneously against a capacity-10 bucket and asserts exactly 10 `Allowed` and 90 `Denied`. The test uses `CountDownLatch(1)` as a starting gun to maximise contention. `@RepeatedTest(10)` surfaces non-deterministic flakiness if the fix is incomplete.

---

## Key Design Decisions

**`milliTokens` integer representation:** Token count is stored as `Long` milliTokens (1 token = 1000 milliTokens) in `BucketState`, rather than `Double` tokens. This enables reliable integer CAS — `AtomicReference.compareAndSet` compares by object identity, and `data class` equality is used; IEEE 754 doubles can produce non-identical bit patterns for logically equal values when arithmetic is involved, making CAS unreliable. Long integer arithmetic is always exact. The 1000× multiplier also preserves sub-token precision: at 5 tokens/sec, 100ms earns 500 milliTokens (half a token), which accumulates correctly until 1000 milliTokens (one full token) is available.

**`retryAfterSeconds` ceiling division:** The denied response carries a `Retry-After` header value computed as: `ceil(ceil(missingMilliTokens / refillRatePerSecond) / 1000)`. Two nested ceiling divisions convert milliTokens → milliseconds → seconds. For integer refill rates ≥ 1 token/sec, this always returns ≥ 1 second, preventing the pathological `Retry-After: 0` case.

**Clock injection (`Clock` fun interface):** The `Clock` abstraction allows tests to control time precisely — advance by exactly 200ms to verify one-token refill, freeze time to test bucket exhaustion, check sub-token partial refill at 100ms and 199ms. Without this, tests would be flaky (real-time dependent) or require `Thread.sleep()` (slow). `SystemClock` is a Kotlin `object` (singleton) that delegates to `System.nanoTime() / 1_000_000` rather than `System.currentTimeMillis()`. `nanoTime` is monotonic — it never steps backward and is unaffected by NTP wall-clock adjustments (e.g. EC2 chrony corrections on boot). The `fun interface` (SAM) allows stubs as lambdas in tests: `Clock { fixedTime }`.

**Hexagonal architecture (ports and adapters):** `core/domain` and `core/port` have zero Spring/Jakarta imports — they compile and test with no framework on the classpath. All framework code lives in `infra/` (implementations) and `adapter/` (HTTP layer). A single `RateLimiterConfig` `@Configuration` class wires the dependency graph at the edge. No `@Component` or `@Autowired` annotations exist inside `infra/` or `adapter/` — explicit constructor injection makes the wiring visible and testable.

**Exception-based thin handler:** `RateLimitHandler` is free of HTTP status decisions. On denial, it throws `RateLimitDeniedException` (extends `RuntimeException`). A separate `@RestControllerAdvice` (`RateLimitExceptionHandler`) owns the 429 mapping, `Retry-After` header, and `{"allowed":false}` body. This separates the "what happened" (domain layer) from the "how to communicate it" (HTTP layer). The `@ExceptionHandler` returning `ResponseEntity` works for both annotated controllers and functional (`coRouter`) routes as of Spring Framework 5.3.

---

## Trade-offs

| Concern | Decision | Alternative not taken |
|---|---|---|
| State storage | In-memory `ConcurrentHashMap` | Redis (out of scope for prototype) |
| Burst handling | Token Bucket (natural burst accumulation) | Sliding Window (strict, more state per key) |
| Refill mechanism | Lazy on each `tryConsume` call | Background scheduler thread |
| Thread safety | Lock-free CAS loop | `synchronized` / `ReentrantLock` |
| Token precision | `milliTokens` Long (exact integer CAS) | `Double` (IEEE 754 unreliable for CAS) |
| Config | Spring `@Value` + `application.yaml` | Config server / hot reload |
| HTTP error mapping | `@RestControllerAdvice` + custom exception | `when` expression inline in handler |
| Request validation | `BodyValidator` throws `BadRequestException` | Jakarta Validation filter / MVC binding |
| Clock source | `System.nanoTime()` (monotonic) | `System.currentTimeMillis()` (wall-clock, non-monotonic) |

### Storage: In-Memory vs Redis

**Why in-memory is the right choice for this challenge.** This prototype runs on a single EC2 t2.micro instance (1 vCPU, 1 GB RAM). With a single JVM process, `ConcurrentHashMap<RateLimitKey, AtomicReference<BucketState>>` in `InMemoryBucketStore` is strictly sufficient: there is no cross-process state to share, no network round-trip on every request, and no serialization cost. The lock-free CAS loop in `TokenBucketRateLimiter` runs in-process against in-memory state, avoiding the network round-trips and serialization overhead required by an external store. For a simulation or prototype, in-memory is the correct scope.

**Why Redis would be required in production.** The moment the API runs on more than one instance (load-balanced, auto-scaled, or multi-region), each JVM holds an independent `ConcurrentHashMap`. Two instances serving the same `RateLimitKey` apply independent token budgets — effectively multiplying the allowed rate by the instance count. This defeats the purpose of rate limiting. Redis (or any shared external store) solves this by centralising state so all instances read and write the same bucket.

**Why adding Redis is non-trivial here.** The `BucketStore` port contract returns `AtomicReference<BucketState>`:

```kotlin
fun interface BucketStore {
    fun getOrCreate(key: RateLimitKey, initial: () -> BucketState): AtomicReference<BucketState>
}
```

`AtomicReference` is a JVM in-process primitive — it has no meaning in Redis. A Redis-backed implementation cannot implement this interface as-is. A real migration would require:

1. **Port redesign**: replace `AtomicReference<BucketState>` with an abstraction that can cross a network boundary (e.g., `suspend fun tryConsumeAtomically(key, update): Boolean`).
2. **Atomic Redis operation**: the CAS loop must become a **Lua script** executed via `EVAL` (Lua runs atomically inside Redis, preventing the same TOCTOU race). A `WATCH`/`MULTI`/`EXEC` transaction is an alternative but adds round-trips.
3. **Serialization**: `BucketState` (two `Long` fields) must be encoded into Redis (hash fields or a single packed string) and decoded on every call.
4. **TTL / eviction**: `ConcurrentHashMap` grows indefinitely for inactive keys. Redis would add `EXPIRE` per key to evict stale buckets, which has no equivalent in the current design.
5. **Test infrastructure**: unit tests using a fake `Clock` would need `Testcontainers` + a real Redis instance for integration-level testing of the Lua script.

**What this prototype does NOT do:** Distributed state (all state is per-JVM instance), persistent storage across restarts, key expiry/eviction, per-user config overrides, or metrics per key. These are intentional omissions — the challenge asks for a working prototype that demonstrates the algorithm and thread-safety model, not a production distributed system.

---

## Configuration Reference (`application.yaml`)

The two tuneable parameters live under the `rate-limiter` prefix and map to `TokenBucketConfig`:

```yaml
rate-limiter:
  capacity: 10              # maximum tokens a bucket can hold
  refill-rate-per-second: 5 # tokens added per second (lazy, computed on each request)
```

| Parameter | Type | Default | Effect |
|---|---|---|---|
| `capacity` | `Long` | `10` | Sets the burst ceiling. A fresh bucket starts full. After exhaustion a client must wait for at least `1 / refillRatePerSecond` seconds before the next token is available. Raising capacity allows larger bursts; lowering it restricts them. |
| `refill-rate-per-second` | `Long` | `5` | How quickly tokens regenerate. At 5 tokens/sec, one token is added every 200 ms. A client that is idle for 2 seconds earns 10 tokens (capped at `capacity`). Raising the rate makes the limiter more lenient under sustained traffic; lowering it makes it stricter. |

**Example: strict API key protection**

```yaml
rate-limiter:
  capacity: 3
  refill-rate-per-second: 1
```

With these settings a client may burst 3 requests immediately, then is limited to 1 request/sec. The `Retry-After` header on the 4th request reports `1` second.

**Example: generous tier for batch clients**

```yaml
rate-limiter:
  capacity: 100
  refill-rate-per-second: 50
```

100-request bursts are allowed; the bucket refills at 50 tokens/sec (one token every 20 ms).

---

## Kotlin Language Patterns

This section explains Kotlin-specific constructs used in the implementation for developers familiar with OOP but new to Kotlin.

### `@JvmInline value class` — `RateLimitKey`

```kotlin
@JvmInline
value class RateLimitKey(val value: String)
```

A `value class` wraps a single primitive or object but is **erased at the JVM bytecode level** — at runtime it is represented directly as the wrapped type (here, a `String`) with zero object allocation overhead. Compare to a regular wrapper class which always allocates a heap object.

`@JvmInline` triggers the JVM erasure. Without it the wrapper exists as a full object.

**Why use it for `RateLimitKey`?**
- Every HTTP request creates a `RateLimitKey` from the request body's `key` field. Using a plain `String` everywhere would allow accidental misuse (passing a user ID where a request ID is expected). The value class provides **compile-time type safety at zero runtime cost**.
- `ConcurrentHashMap` key lookup uses `equals` and `hashCode`. `value class` delegates both to the wrapped `String`, so map performance is identical to using a raw `String` key.

### `data class` — `BucketState` and `TokenBucketConfig`

```kotlin
data class BucketState(val milliTokens: Long, val lastRefillAt: Long)
```

A `data class` auto-generates `equals`, `hashCode`, `toString`, and `copy`. The `copy` method is critical for the CAS loop:

```kotlin
val next = refilled.copy(milliTokens = refilled.milliTokens - ONE_MILLI_TOKEN)
```

`copy` creates a **new immutable instance** with one field changed, leaving the original unchanged. This is the correct pattern for CAS — `compareAndSet(current, next)` requires `current` and `next` to be distinct objects. Mutating `current` in place would break the atomicity guarantee.

### `fun interface` — `Clock`

```kotlin
fun interface Clock {
    fun nowMillis(): Long
}
```

A `fun interface` (Kotlin's SAM — Single Abstract Method — interface) allows any lambda with the matching signature to be used as a `Clock` without a named class:

```kotlin
// Production: SystemClock object
val clock: Clock = SystemClock

// Test: lambda stub
val clock = Clock { fixedTime }   // equivalent to Clock { -> fixedTime }
```

This is identical in concept to Java's `@FunctionalInterface`. The `fun` keyword is what enables the lambda syntax on the call site. Without it, test code would require a verbose anonymous object (`object : Clock { override fun nowMillis() = fixedTime }`).

### `object` — `SystemClock` (Kotlin singleton)

```kotlin
object SystemClock : Clock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000
}
```

`object` declares a class and its single instance in one statement — the Kotlin equivalent of the Singleton pattern with guaranteed thread-safe initialization (backed by the JVM class loader). No `companion object`, no `getInstance()` method, no `static` field.

### `suspend fun` — Coroutines in the handler

```kotlin
suspend fun check(request: ServerRequest): ServerResponse { ... }
```

`suspend fun` marks a function that can be paused and resumed without blocking a thread. In this service, `request.awaitBody<RateLimitRequest>()` suspends while the request body is read from the network — the underlying Netty thread is released and available for other requests during that wait. This is the Kotlin Coroutines model for non-blocking I/O, which integrates with Spring WebFlux's `Reactor` event loop. Non-`suspend` blocking code in a handler would hold the Netty thread, degrading throughput under load.

---

## Glossary

| Term | Definition |
|---|---|
| **Token Bucket** | A rate limiting algorithm where requests consume tokens from a bucket. The bucket refills at a fixed rate up to a maximum capacity. A request is allowed only if at least one token is available. |
| **Bucket** | The per-key state holding the current token count and the timestamp of the last refill. Each unique `key` in the request body gets its own independent bucket. |
| **Token** | A unit of request capacity. One token = permission to make one request. Stored internally as 1000 milliTokens for integer arithmetic precision. |
| **milliToken** | Internal representation: 1 token = 1000 milliTokens. Enables sub-token precision (e.g., at 5 tokens/sec, 100ms earns exactly 500 milliTokens) without floating-point arithmetic. |
| **Capacity** | The maximum number of tokens a bucket can hold (`rate-limiter.capacity`). Determines the maximum burst size. |
| **Refill rate** | Tokens added per second (`rate-limiter.refill-rate-per-second`). Controls the sustained throughput ceiling. |
| **Lazy refill** | Refill is computed on each `tryConsume()` call from elapsed time — not by a background thread. The bucket's current balance is always `min(previous + earned, capacity)` at the moment of the request. |
| **CAS (Compare-And-Swap)** | An atomic CPU instruction (exposed in Java as `AtomicReference.compareAndSet`). Writes a new value only if the current value equals an expected value — otherwise it fails and the caller retries. Used here to update `BucketState` without locks. |
| **TOCTOU (Time-Of-Check-Time-Of-Use)** | A race condition where a value is checked (tokens available?) and then used (tokens subtracted), but another thread modifies the value between the check and the use. The CAS loop eliminates this by making check and use a single atomic operation. |
| **Lock-free** | A concurrency strategy that does not use mutexes or blocking primitives (`synchronized`, `ReentrantLock`). Threads never block waiting for each other — they retry on collision. Enables higher throughput under contention compared to locking. |
| **Hexagonal Architecture** | Also called Ports and Adapters. The business logic (`core/`) depends only on interfaces (ports). External frameworks (Spring, HTTP) live in adapters and plug into those interfaces. The core can be tested and reasoned about independently of any framework. |
| **Port** | An interface in `core/port/` that abstracts an external concern (time: `Clock`; state: `BucketStore`; the algorithm itself: `RateLimiterPort`). |
| **Adapter** | An implementation of a port that uses a specific technology (`SystemClock` wraps `System.nanoTime()`; `InMemoryBucketStore` wraps `ConcurrentHashMap`). |
| **coRouter** | Spring WebFlux's Kotlin coroutine DSL for declaring HTTP routes (`coRouter { POST("/path", handler::fn) }`). Unlike `@RestController`, it keeps routing and request handling in separate classes. |
| **Monotonic clock** | A clock that only moves forward, unaffected by NTP adjustments or manual time changes. `System.nanoTime()` is monotonic. `System.currentTimeMillis()` is a wall clock and can step backward during NTP corrections, which would cause negative elapsed time and therefore negative token refill without the `maxOf(0L, ...)` guard. |
| **`Retry-After`** | An HTTP response header (RFC 7231) that tells the client how many seconds to wait before retrying. Returned with HTTP 429. Computed via ceiling division to ensure the value is always ≥ 1 second. |
| **`@JvmInline value class`** | A Kotlin construct that wraps a single value with a distinct type for compile-time safety, but is erased to the wrapped type at the JVM level — zero allocation overhead. |
| **`data class`** | A Kotlin class that auto-generates `equals`, `hashCode`, `toString`, and `copy`. The `copy` method is essential for immutable CAS updates. |
| **`fun interface` (SAM)** | A Kotlin interface with a single abstract method. Enables lambda syntax on the call site. Used for `Clock` to allow test stubs as one-liners (`Clock { fixedTime }`). |
| **`object`** | Kotlin's built-in singleton declaration. Creates a class and its single thread-safe instance in one statement. Used for `SystemClock`. |
| **`suspend fun`** | A Kotlin coroutine function that can suspend (yield the thread) without blocking. Used in WebFlux handlers for non-blocking I/O. |
| **CountDownLatch** | A Java synchronization primitive used in `TokenBucketConcurrencyTest`. Initialized to 1, it holds all 100 threads at a gate until `countDown()` is called, maximising contention for the race test. |

---

## How AI Was Used

This implementation was built collaboratively with **Claude Code** (claude-sonnet-4-6) using a structured TDD incremental workflow across 7 pull requests (PR 0 + Increments 2–6).

**Planning phase:** The developer reviewed the challenge requirements (`CHALLENGE.md`) and approved a detailed implementation plan including algorithm choice rationale, the `milliTokens` integer-CAS design, the `@JvmInline value class` for type-safe keys, the concurrency test design with `CountDownLatch`, and the 6-increment delivery sequence. Each design decision was discussed and understood before the plan was approved.

**Implementation:** Claude generated source files following the approved plan in a strict RED → GREEN → REFACTOR TDD cycle: failing tests were committed first, then production code. Key technical decisions made during implementation — the `retryAfterSeconds` two-step ceiling division, the exception-based thin handler (`RateLimitDeniedException` + `@RestControllerAdvice`), the `LocalValidatorFactoryBean` for standalone test validation, and the Log4j2 conflict resolution (excluding `log4j-to-slf4j` + `spring-boot-starter-logging`) — were each reviewed and understood by the developer before commit.

**Tooling:** Claude Code was configured with project-specific automations:
- **ktlint-format-on-save hook** — auto-formats Kotlin files before detekt runs
- **Detekt on-save hook** — static analysis runs automatically after every Kotlin edit
- **Build file guard hook** — warns before editing `build.gradle.kts` or `gradle.properties`
- **`/quality-gate` skill** — runs `./gradlew test detekt` and reports results
- **`/design-doc` skill** — updates this file based on current source
- **`/increment` skill** — guides TDD increment workflow (branch, red-green-refactor, PR)
- **`architecture-guardian` subagent** — verifies zero Spring imports in `core/`
- **context7 MCP server** — live Spring Boot 4.x / Kotlin documentation during development

All AI-generated code was reviewed, understood, and approved by the developer before commit. Any logic that is not immediately self-evident (the milliToken arithmetic, the CAS retry condition, the `retryAfterSeconds` ceiling formula) is documented with explanatory comments in the source.
