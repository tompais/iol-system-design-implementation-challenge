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

**Exception-based thin handler:** `RateLimitHandler` is free of HTTP status decisions. On denial, it throws `RateLimitExceededException` (extends `ResponseStatusException(429)`). A separate `@RestControllerAdvice` (`RateLimitExceptionHandler`) owns the 429 mapping, `Retry-After` header, and `{"allowed":false}` body. This separates the "what happened" (domain layer) from the "how to communicate it" (HTTP layer). The `@ExceptionHandler` returning `ResponseEntity` works for both annotated controllers and functional (`coRouter`) routes as of Spring Framework 5.3.

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
| Request validation | Spring `Validator` injected into handler | Jakarta Validation filter / MVC binding |
| Clock source | `System.nanoTime()` (monotonic) | `System.currentTimeMillis()` (wall-clock, non-monotonic) |

**What this prototype does NOT do:** Distributed state (all state is per-JVM instance), persistent storage across restarts, key expiry/eviction, per-user config overrides, or metrics per key. Swapping `InMemoryBucketStore` for a Redis-backed implementation would require the CAS loop to become a Lua script or `WATCH`/`MULTI`/`EXEC` Redis transaction — the `BucketStore` port is designed for exactly this swap.

---

## How AI Was Used

This implementation was built collaboratively with **Claude Code** (claude-sonnet-4-6) using a structured TDD incremental workflow across 7 pull requests (PR 0 + Increments 2–6).

**Planning phase:** The developer reviewed the challenge requirements (`CHALLENGE.md`) and approved a detailed implementation plan including algorithm choice rationale, the `milliTokens` integer-CAS design, the `@JvmInline value class` for type-safe keys, the concurrency test design with `CountDownLatch`, and the 6-increment delivery sequence. Each design decision was discussed and understood before the plan was approved.

**Implementation:** Claude generated source files following the approved plan in a strict RED → GREEN → REFACTOR TDD cycle: failing tests were committed first, then production code. Key technical decisions made during implementation — the `retryAfterSeconds` two-step ceiling division, the exception-based thin handler (`RateLimitExceededException` + `@RestControllerAdvice`), the `LocalValidatorFactoryBean` for standalone test validation, and the Log4j2 conflict resolution (excluding `log4j-to-slf4j` + `spring-boot-starter-logging`) — were each reviewed and understood by the developer before commit.

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
