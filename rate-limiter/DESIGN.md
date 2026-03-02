# Rate Limiter — Design Document

## Algorithm Choice: Token Bucket

**Chosen algorithm:** Token Bucket with lazy refill.

The two main candidates were Token Bucket and Sliding Window Counter. Token Bucket was chosen because it models real-world bursty traffic naturally — tokens accumulate during quiet periods and drain during bursts — which matches production API behavior. A client that is quiet for 2 seconds earns 2× capacity tokens (capped at `capacity`), allowing a legitimate burst, rather than being penalized for the previous window's inactivity. Sliding Window Counter requires a sorted set of request timestamps per key and needs a background sweep for cleanup, adding operational complexity with no benefit for this use case. Fixed Window Counter has a well-known boundary exploit (2× burst at window edges) that makes it unsuitable for rate limiting.

**Concrete example** (with the defaults from `application.yaml`: `capacity=10`, `refillRatePerSecond=5`):

```
t=0s   → bucket starts full: 10 tokens
t=0s   → 10 rapid requests arrive → bucket drains to 0 tokens → all 10 allowed
t=0s   → 11th request → denied, Retry-After: 1s (at 5 t/s, 1 token replenishes in 200ms → ceil to 1s)
t=1s   → 5 new tokens have been earned → 5 requests now allowed, bucket back to 0
t=3s   → 10 more tokens earned (capped at capacity=10) → 10 requests burst-allowed again
```

**Lazy refill vs. scheduled background thread:** Refill is computed on each `tryConsume()` call by comparing the current timestamp to the `lastRefillAt` timestamp stored in `BucketState`. `earned = min(refillRatePerSecond × elapsedMs, capacity × 1000)` (units cancel: tokens/sec × ms = milliTokens). The cap on `earned` prevents Long overflow and unexpectedly huge balances if the clock jumps far forward. `elapsedMs` is itself guarded with `maxOf(0L, ...)` so a backward clock step never produces negative earned tokens. This eliminates the need for a background scheduler thread entirely — no goroutines, no timers, no coordination overhead, just arithmetic on each request. The trade-off is that a burst of concurrent requests all compute the same refill amount simultaneously; this is correct because the CAS loop serialises which write wins.

---

## Configuration Parameters (`application.yaml`)

The rate limiter exposes two parameters under the `rate-limiter` namespace. Changing either value alters the algorithm's behavior without touching the code.

```yaml
rate-limiter:
  capacity: 10               # Maximum number of tokens the bucket can hold
  refill-rate-per-second: 5  # Tokens added to the bucket per second
```

**`capacity`** — the maximum burst size. This is the upper bound on how many requests a single key can make in a very short window (e.g., within a single millisecond). It also caps the tokens that accumulate during idle periods. Setting `capacity: 1` effectively turns the limiter into a strict 1-req/sec throttle with no burst; setting `capacity: 100` allows a burst of 100 requests after an idle period. Mapped to `TokenBucketConfig.capacity` via `@Value("${rate-limiter.capacity}")`.

**`refill-rate-per-second`** — the sustained request rate. Tokens are earned at this rate continuously: a client can sustain exactly `refillRatePerSecond` requests per second indefinitely once the initial burst is consumed. Setting `refillRatePerSecond: 1` means one token every 1000ms; setting it to `10` means one token every 100ms. This value also drives the `Retry-After` header — the higher the refill rate, the shorter the wait after a denial. Mapped to `TokenBucketConfig.refillRatePerSecond`.

**Interaction between the two:** `capacity` controls burst tolerance; `refillRatePerSecond` controls steady-state throughput. A useful mental model:

```
capacity=10, refillRatePerSecond=5
→ burst allowance:       up to 10 requests instantly
→ sustained rate:        5 requests/sec after the burst is spent
→ full bucket refill:    10 / 5 = 2 seconds of inactivity to restore all tokens
```

Both values are `Long` in `TokenBucketConfig` so they participate cleanly in the milliToken integer arithmetic (see Key Design Decisions below).

---

## Thread-Safety Model

**The race condition:** Multiple HTTP threads may hit the same rate-limit key simultaneously (e.g., a mobile app sending parallel API requests with a shared user key). Without atomicity, the TOCTOU (Time-Of-Check-Time-Of-Use) race occurs: Thread A reads `milliTokens=1000`, Thread B reads `milliTokens=1000`, both decide "allowed", both subtract 1000 — the same token is spent twice and both requests pass when only one should.

**Solution:** `AtomicReference<BucketState>` + Compare-And-Swap (CAS) loop in `TokenBucketRateLimiter.tryConsume`. The loop reads the current state, computes the refilled state, and calls `ref.compareAndSet(current, next)`. The JVM atomically writes `next` only if the reference still points to `current`. If another thread won the race and mutated the reference first, `compareAndSet` returns `false` and the loop retries with a fresh `ref.get()`. This is lock-free — no `synchronized` blocks, no `ReentrantLock`, no thread blocking.

`InMemoryBucketStore` uses `ConcurrentHashMap.computeIfAbsent` for atomic key creation, ensuring exactly one `AtomicReference<BucketState>` is shared per key even under concurrent first-access. The `BucketStore` port is defined to return `AtomicReference` directly — callers must share the same reference object, not copies of it.

**Validation:** `TokenBucketConcurrencyTest` runs 100 threads simultaneously against a capacity-10 bucket and asserts exactly 10 `Allowed` and 90 `Denied`. The test uses `CountDownLatch(1)` as a starting gun to maximise contention. `@RepeatedTest(10)` surfaces non-deterministic flakiness if the fix is incomplete.

---

## Key Design Decisions

**`milliTokens` integer representation:** Token count is stored as `Long` milliTokens (1 token = 1000 milliTokens) in `BucketState`, rather than `Double` tokens. This enables reliable integer CAS — `AtomicReference.compareAndSet` compares by object identity, and `data class` equality is used; IEEE 754 doubles can produce non-identical bit patterns for logically equal values when arithmetic is involved, making CAS unreliable. Long integer arithmetic is always exact. The 1000× multiplier also preserves sub-token precision: at 5 tokens/sec, 100ms earns 500 milliTokens (half a token), which accumulates correctly until 1000 milliTokens (one full token) is available.

**Step-by-step arithmetic example** (`capacity=10`, `refillRatePerSecond=5`):

```
Initial state:  milliTokens = 10 * 1000 = 10_000

Request at t=0ms:
  elapsedMs = 0 → earned = 0
  milliTokens after consume:  10_000 - 1_000 = 9_000   → allowed

Request at t=100ms (same key, 1 token already spent):
  elapsedMs = 100ms
  earned = 5 [tokens/sec] × 100 [ms] = 500 milliTokens  (half a token)
  milliTokens before consume: min(9_000 + 500, 10_000) = 9_500
  milliTokens after consume:  9_500 - 1_000 = 8_500      → allowed

Burst at t=0ms (10 requests, bucket starts full):
  After 10 requests: milliTokens = 0
  11th request:
    milliTokens = 0 < 1_000 → denied
    missingMilliTokens = 1_000 - 0 = 1_000
    msNeeded = ceil(1_000 / 5) = 200ms
    retryAfterSeconds = ceil(200 / 1000) = 1   → Retry-After: 1
```

**`retryAfterSeconds` ceiling division:** The denied response carries a `Retry-After` header value computed as two nested integer ceiling divisions: `ceil(ceil(missingMilliTokens / refillRatePerSecond) / 1000)`. This converts milliTokens → milliseconds → seconds. For integer refill rates ≥ 1 token/sec, this always returns ≥ 1 second, preventing the pathological `Retry-After: 0` case. The ceiling formula uses integer arithmetic: `ceil(a / b) = (a + b - 1) / b`.

**Clock injection (`Clock` fun interface):** The `Clock` abstraction allows tests to control time precisely — advance by exactly 200ms to verify one-token refill, freeze time to test bucket exhaustion, check sub-token partial refill at 100ms and 199ms. Without this, tests would be flaky (real-time dependent) or require `Thread.sleep()` (slow). `SystemClock` is a Kotlin `object` (singleton) that delegates to `System.nanoTime() / 1_000_000` rather than `System.currentTimeMillis()`. `nanoTime` is monotonic — it never steps backward and is unaffected by NTP wall-clock adjustments (e.g. EC2 chrony corrections on boot). The `fun interface` (SAM) allows stubs as lambdas in tests: `Clock { fixedTime }`.

**Hexagonal architecture (ports and adapters):** `core/domain` and `core/port` have zero Spring/Jakarta imports — they compile and test with no framework on the classpath. All framework code lives in `infra/` (implementations) and `adapter/` (HTTP layer). A single `RateLimiterConfig` `@Configuration` class wires the dependency graph at the edge. No `@Component` or `@Autowired` annotations exist inside `infra/` or `adapter/` — explicit constructor injection makes the wiring visible and testable.

**Exception-based thin handler:** `RateLimitHandler` is free of HTTP status decisions. On denial, it throws `RateLimitDeniedException` (extends `RuntimeException`). A separate `@RestControllerAdvice` (`RateLimitExceptionHandler`) owns the 429 mapping, `Retry-After` header, and `{"allowed":false}` body. This separates the "what happened" (domain layer) from the "how to communicate it" (HTTP layer). The `@ExceptionHandler` returning `ResponseEntity` works for both annotated controllers and functional (`coRouter`) routes as of Spring Framework 5.3.

---

## Trade-offs

| Concern | Decision | Why this was chosen | Alternative not taken | Why rejected |
|---|---|---|---|---|
| State storage | In-memory `ConcurrentHashMap` | Zero latency, no infra, correct for single-instance prototype | Redis | Requires port redesign + Lua scripts + Testcontainers; out of scope |
| Burst handling | Token Bucket | Natural burst accumulation; clients earn tokens during idle periods | Sliding Window Counter | Needs sorted timestamp sets per key + background sweep for cleanup |
| Refill mechanism | Lazy on each `tryConsume` call | No background threads; simpler; correct because CAS serialises writes | Scheduled background thread | Added scheduler complexity; no correctness benefit in single-process |
| Thread safety | Lock-free CAS loop | Non-blocking; no thread starvation; scales with CPU count | `synchronized` / `ReentrantLock` | Blocks threads; under high concurrency a locked bucket queues all competing threads |
| Token precision | `milliTokens` Long (exact integer CAS) | Integer arithmetic is always exact; avoids IEEE 754 pitfalls in CAS | `Double` | Non-identical bit patterns for logically equal floats make CAS unreliable |
| Config | Spring `@Value` + `application.yaml` | Simple, readable, effective on restart | Config server / hot reload | Unnecessary operational complexity for a single-service prototype |
| HTTP error mapping | `@RestControllerAdvice` + custom exception | Clean separation: domain throws, adapter maps to HTTP | `when` expression inline in handler | Mixes domain decisions with HTTP concerns; violates Single Responsibility |
| Request validation | `BodyValidator` throws `BadRequestException` | Explicit, testable, no magic; integrates with Jakarta Validator | Jakarta Validation filter / MVC binding | WebFlux functional routes don't auto-trigger `@Valid`; would need a filter |
| Clock source | `System.nanoTime()` (monotonic) | Immune to NTP jumps; elapsed time is a pure delta, never an absolute | `System.currentTimeMillis()` | Non-monotonic: an NTP correction mid-run can produce negative elapsed time → negative refill |
| Fixed Window Counter | — | Not applicable | Fixed Window Counter | 2× burst exploit at window boundaries; fundamentally unsuitable for rate limiting |

### Storage: In-Memory vs Redis

**Why in-memory is the right choice for this challenge.** This prototype runs on a single EC2 t2.micro instance (1 vCPU, 1 GB RAM). With a single JVM process, `ConcurrentHashMap<RateLimitKey, AtomicReference<BucketState>>` in `InMemoryBucketStore` is strictly sufficient: there is no cross-process state to share, no network round-trip on every request, and no serialization cost. The lock-free CAS loop in `TokenBucketRateLimiter` runs in-process against in-memory state, avoiding the network round-trips and serialization overhead required by an external store. For a simulation or prototype, in-memory is the correct scope.

**Why Redis would be required in production.** The moment the API runs on more than one instance (load-balanced, auto-scaled, or multi-region), each JVM holds an independent `ConcurrentHashMap`. Two instances serving the same `RateLimitKey` apply independent token budgets — effectively multiplying the allowed rate by the instance count. This defeats the purpose of rate limiting. Redis (or any shared external store) solves this by centralising state so all instances read and write the same bucket.

**Why adding Redis is non-trivial here.** The `BucketStore` port contract returns `AtomicReference<BucketState>`:

```kotlin
fun interface BucketStore {
    fun getOrCreate(key: RateLimitKey, initial: () -> BucketState): AtomicReference<BucketState>
}
```

`AtomicReference` is a JVM in-process primitive — it has no meaning in Redis. A real migration would require:

1. **Port redesign**: replace `AtomicReference<BucketState>` with an abstraction that can cross a network boundary (e.g., `suspend fun tryConsumeAtomically(key, update): Boolean`).
2. **Atomic Redis operation**: the CAS loop must become a **Lua script** executed via `EVAL` (Lua runs atomically inside Redis, preventing the same TOCTOU race). A `WATCH`/`MULTI`/`EXEC` transaction is an alternative but adds round-trips.
3. **Serialization**: `BucketState` (two `Long` fields) must be encoded into Redis (hash fields or a single packed string) and decoded on every call.
4. **TTL / eviction**: `ConcurrentHashMap` grows indefinitely for inactive keys. Redis would add `EXPIRE` per key to evict stale buckets, which has no equivalent in the current design.
5. **Test infrastructure**: unit tests using a fake `Clock` would need `Testcontainers` + a real Redis instance for integration-level testing of the Lua script.

**What this prototype does NOT do:** Distributed state (all state is per-JVM instance), persistent storage across restarts, key expiry/eviction, per-user config overrides, or metrics per key. These are intentional omissions — the challenge asks for a working prototype that demonstrates the algorithm and thread-safety model, not a production distributed system.

---

## Kotlin Concepts for OOP Programmers

This section explains Kotlin-specific constructs used in the codebase for readers who are familiar with object-oriented programming but not with Kotlin.

### `@JvmInline value class RateLimitKey`

```kotlin
@JvmInline
value class RateLimitKey(val value: String)
```

In most OOP languages, wrapping a `String` in a class adds a heap allocation and an extra pointer dereference every time the wrapper is used. A Kotlin `value class` is a **compile-time wrapper**: at runtime, the JVM erases it and uses the underlying `String` directly — zero extra allocation, zero runtime overhead.

Why use it instead of a plain `String`? Type safety. Without `RateLimitKey`, a function signature like `fun tryConsume(key: String)` gives no indication of what kind of string is expected, and callers could accidentally pass a user ID, a session token, or a URL. With `RateLimitKey`, the type system enforces intent at compile time. The `@JvmInline` annotation opts in to the JVM erasure optimization (it prevents boxing). Equality is structural — two `RateLimitKey("user-1")` instances are equal — which is what `ConcurrentHashMap` key lookup requires.

### `fun interface Clock`

```kotlin
fun interface Clock {
    fun nowMillis(): Long
}
```

A `fun interface` (also called a SAM — Single Abstract Method — interface) is a Kotlin interface with exactly one abstract method. The key benefit is that any lambda expression automatically becomes a valid implementation:

```kotlin
// In tests — no anonymous class boilerplate:
val frozenClock = Clock { 1_000L }
// Equivalent Java: new Clock() { @Override public long nowMillis() { return 1_000L; } }
```

In Java you would need a full anonymous class declaration. In Kotlin, `fun interface` makes the lambda syntax work. This is purely a syntactic convenience; at the bytecode level, an anonymous class is still generated, but call-sites are far more readable, especially in test fixtures that inject different clock values per test case.

### `data class BucketState`

```kotlin
data class BucketState(val milliTokens: Long, val lastRefillAt: Long)
```

A Kotlin `data class` auto-generates `equals()`, `hashCode()`, `toString()`, and `copy()`. For `BucketState`, this matters for two reasons:

1. **`copy()` for immutable updates**: instead of mutating fields, the algorithm creates a new `BucketState` with updated `milliTokens` via `state.copy(milliTokens = ...)`. Immutability ensures threads never see partially-written state.
2. **CAS note**: `AtomicReference.compareAndSet(current, next)` compares by object identity (`===`), not structural equality. The CAS loop works because `current` is the exact reference object read from the `AtomicReference` — not a logically-equal copy. The `data class` `equals()` is not involved in the CAS check itself; the identity guarantee comes from the fact that `ref.get()` returns the same object reference that was last written.

### `object SystemClock`

```kotlin
object SystemClock : Clock { ... }
```

Kotlin's `object` keyword declares a **singleton** — a class with exactly one instance, created lazily on first access. Unlike Java's Singleton design pattern (with its `getInstance()` boilerplate), Kotlin `object` is first-class: it implements interfaces, can be passed as a parameter, and is thread-safe by the JVM class-loader guarantee. `SystemClock` is the production clock injected in `RateLimiterConfig`; tests replace it with a lambda.

### `minOf` / `maxOf` instead of `Math.min` / `Math.max`

Kotlin's standard library provides `minOf(a, b)` and `maxOf(a, b)` as top-level functions that work for any `Comparable` type. They read more naturally in arithmetic expressions and do not require the `Math.` prefix. In this codebase, `maxOf(0L, elapsedMs)` guards against negative clock deltas, and `minOf(earned, capacity * ONE_MILLI_TOKEN)` caps the refill at bucket capacity.

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

---

## Glossary

Quick reference for terms used throughout this document and the source code.

| Term | Definition |
|---|---|
| **Token Bucket** | A rate-limiting algorithm. Tokens accumulate at a fixed rate up to a maximum (the bucket capacity). Each request consumes one token. If the bucket is empty, the request is denied. |
| **Lazy refill** | Tokens are not added on a timer. Instead, the refill is computed on-demand when a request arrives, based on how much time has elapsed since the last refill. |
| **milliTokens** | Internal unit used instead of fractional tokens. 1 token = 1000 milliTokens. Allows sub-token accumulation (e.g., half a token = 500 milliTokens) using exact integer arithmetic. |
| **CAS (Compare-And-Swap)** | A hardware-level atomic operation: "set this memory location to `new` only if it currently holds `expected`." Returns true on success, false if another thread changed the value in between. Used here via `AtomicReference.compareAndSet`. |
| **CAS loop** | A retry loop that repeatedly reads, computes, and attempts a CAS write. If the CAS fails (another thread won), it reads the freshly-written value and tries again. Correct and live-lock-free under the assumption that at least one thread always makes progress. |
| **TOCTOU (Time-Of-Check-Time-Of-Use)** | A class of race condition where state is read ("checked") and then acted upon ("used"), but another thread modifies the state between the two steps. Classic example: two threads both read `balance = 1`, both decide to spend it, both write `balance = 0` — spending the same unit twice. |
| **AtomicReference** | A JVM class (`java.util.concurrent.atomic.AtomicReference<T>`) that wraps an object reference and exposes `get`, `set`, and `compareAndSet` as atomic operations without locks. |
| **Lock-free** | A concurrency strategy where threads never block each other. Progress is guaranteed because at least one thread always completes its operation, even if others are retrying. Contrast with lock-based: a thread holding a lock prevents all others from proceeding. |
| **Monotonic clock** | A clock that only moves forward, regardless of wall-clock corrections (e.g., NTP). `System.nanoTime()` is monotonic. `System.currentTimeMillis()` is not — an NTP step can move it backward, causing negative elapsed time in delta calculations. |
| **NTP (Network Time Protocol)** | The protocol that synchronises system clocks to an authoritative time source. On cloud VMs (e.g., EC2), NTP can step the clock forward or backward by milliseconds or seconds on boot, which would corrupt elapsed-time calculations if a non-monotonic clock were used. |
| **SAM (Single Abstract Method)** | An interface with exactly one abstract method. In Kotlin, a `fun interface` is a SAM interface, allowing it to be implemented with a lambda expression directly. Java's functional interfaces (`Runnable`, `Comparator`) are also SAMs. |
| **`@JvmInline value class`** | A Kotlin compile-time wrapper erased to its underlying type at runtime. Zero heap allocation overhead vs. a regular wrapper class. Used for type-safe identifiers (`RateLimitKey`) without runtime cost. |
| **`data class`** | A Kotlin class where `equals`, `hashCode`, `toString`, and `copy` are auto-generated from the constructor properties. Structural equality: two instances with the same field values are considered equal. |
| **`object` (Kotlin singleton)** | A Kotlin construct that declares a class with exactly one instance. Thread-safe by JVM class-loader guarantee. Used for `SystemClock` — a stateless implementation that does not need to be instantiated more than once. |
| **Hexagonal architecture** | Also called Ports and Adapters. The domain core (`core/`) knows nothing about the framework; it exposes interfaces (ports) that infra and adapters implement. Dependency direction always points inward: adapters → ports ← infra. |
| **`coRouter`** | A Spring WebFlux DSL for declaring functional routes with Kotlin Coroutine support. Routes are lambdas, not annotated methods. Handlers are `suspend fun` — they can `await` reactive operations without blocking a thread. |
| **Ceiling division** | Integer division that rounds up rather than truncating. Formula: `ceil(a / b) = (a + b - 1) / b`. Used in `retryAfterSeconds` to ensure the client always waits at least 1 second, never 0. |
| **IEEE 754** | The international standard for floating-point arithmetic. Defines how `Double` and `Float` values are represented in binary. Can produce non-identical bit patterns for logically equal results of different arithmetic paths — which is why `Double` is not used for CAS comparison in this implementation. |
