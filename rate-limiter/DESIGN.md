# Rate Limiter — Design Document

## Algorithm Choice: Token Bucket

**Chosen algorithm:** Token Bucket with lazy refill.

The two main candidates were Token Bucket and Sliding Window Counter. Token Bucket was chosen because it models real-world bursty traffic naturally — tokens accumulate during quiet periods and drain during bursts — which matches production API behavior. Sliding Window Counter requires a ring buffer or sorted set of timestamps per key and needs a background sweep for cleanup, adding operational complexity with no benefit for this use case. Fixed Window Counter has a well-known boundary exploit (2× burst at window edges) that makes it unsuitable for rate limiting.

**Lazy refill vs. scheduled background thread:** Refill is computed on each `tryConsume()` call by comparing the current timestamp to the last-refill timestamp. This eliminates the need for a background scheduler thread entirely. No goroutines, no timers, no coordination overhead — just arithmetic on each request.

---

## Thread-Safety Model

**Problem:** Multiple HTTP threads may hit the same rate-limit key simultaneously (e.g., a mobile app sending parallel API requests with a shared key). Without atomicity, two threads can both read the same token count, both decide they have enough, and both subtract — spending the same token twice.

**Solution:** `AtomicReference<BucketState>` + Compare-And-Swap (CAS) loop in `TokenBucketRateLimiter`. The CAS operation atomically replaces the current state with the new state only if the current state hasn't changed since it was read. If another thread wins the CAS first, the losing thread retries with the updated state. This is lock-free — no `synchronized` blocks, no `ReentrantLock`, no thread blocking.

`InMemoryBucketStore` uses `ConcurrentHashMap.computeIfAbsent` for atomic key creation, ensuring exactly one `AtomicReference<BucketState>` exists per key even under concurrent access.

**Validation:** `TokenBucketConcurrencyTest` runs 100 threads simultaneously against a capacity-10 bucket and asserts exactly 10 are allowed. The test is `@RepeatedTest(10)` to surface flaky race conditions.

---

## Key Design Decisions

**`milliTokens` integer representation:** Token count is stored as `Long` milliTokens (1 token = 1000 milliTokens) instead of `Double` tokens. This enables integer CAS — floating-point CAS is unreliable because IEEE 754 can produce non-identical bit patterns for the same logical value. Storing as milliTokens preserves sub-token precision (needed for fractional refill over milliseconds) while keeping the CAS correct.

**Clock injection (`Clock` fun interface):** The `Clock` abstraction allows tests to control time precisely — advance by exactly 500ms to verify partial refill, freeze at a moment to test the bucket boundary. Without this, tests would either be flaky (real-time dependent) or require `Thread.sleep()` (slow). `SystemClock` is the production implementation; it's an `object` (singleton) with zero allocation overhead.

**Hexagonal architecture (ports and adapters):** The `core/` package has zero Spring imports. `TokenBucketRateLimiter` depends only on interfaces (`Clock`, `BucketStore`, `RateLimiterPort`) defined in `core/port/`. This makes the core fully unit-testable without starting a Spring context. `RateLimiterConfig` wires everything together at the edge.

**Scope boundary:** This prototype is intentionally in-memory only. A distributed deployment would swap `InMemoryBucketStore` for a Redis-backed store — the `BucketStore` port is designed for this. The CAS loop would need to become a Lua script or Redis transaction. This is documented but not implemented, as the challenge asks for a working prototype, not a full distributed system.

---

## Trade-offs

| Concern | Decision | Alternative not taken |
|---|---|---|
| State storage | In-memory `ConcurrentHashMap` | Redis (out of scope for prototype) |
| Burst handling | Token Bucket (natural burst) | Sliding Window (strict, more state) |
| Refill mechanism | Lazy on each request | Background scheduler thread |
| Thread safety | Lock-free CAS | `synchronized` / `ReentrantLock` |
| Precision | `milliTokens` Long | `Double` (unreliable CAS) |
| Config | Spring `@Value` + `application.yaml` | Config server / hot reload |

---

## How AI Was Used

This implementation was built collaboratively with **Claude Code** (claude-sonnet-4-6) using a TDD incremental workflow.

**Planning phase:** Claude analyzed the challenge requirements (`CHALLENGE.md`, `PROMPT_CONTEXT.md`) and produced a detailed implementation plan including algorithm trade-off table, package structure, test case inventory, and delivery increments. The Token Bucket algorithm choice and the `milliTokens` integer-CAS insight came from this planning session.

**Implementation:** Claude generated all source files following the plan. Each file was reviewed for correctness — the CAS loop logic, the `milliTokens` math, the `@JvmInline value class` usage for map key safety, and the `@WebFluxTest` slice isolation were all discussed and understood before being committed.

**Tooling:** Claude Code was configured with project-specific automations:
- **Detekt on-save hook** — static analysis runs automatically after every Kotlin file edit
- **Build file guard hook** — warns before editing `build.gradle.kts` or `gradle.properties`
- **`/quality-gate` skill** — runs `./gradlew test detekt` and reports results
- **`/design-doc` skill** — updates this file based on the current implementation
- **context7 MCP server** — provides live Spring Boot 4.x documentation during development

All code generated by AI was reviewed, understood, and approved by the developer before commit. Per challenge rules, any function or class that is not fully understood has been documented with explanatory comments.
