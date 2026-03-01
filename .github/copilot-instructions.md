# GitHub Copilot — Rate Limiter Project Instructions

## What this project is

A working prototype of a **Token Bucket rate limiter**, built as a system design
implementation challenge based on "System Design Interview Vol. 1" by Alex Xu.
The goal: correct algorithm, clean architecture, passing tests, documented trade-offs.

---

## Architecture — Hexagonal (Ports and Adapters)

Single Gradle module, 4 logical layers. **Dependency rule: outer layers depend on inner
layers, never the reverse.**

```
com.iol.ratelimiter/
  core/domain/     ← pure Kotlin: RateLimitKey, BucketState, RateLimitResult, TokenBucketConfig
  core/port/       ← interfaces only: Clock, BucketStore, RateLimiterPort
  infra/           ← implementations: SystemClock, InMemoryBucketStore, TokenBucketRateLimiter
  adapter/api/
    errors/
      exceptions/  ← RateLimitExceededException
      handler/     ← RateLimitExceptionHandler (@RestControllerAdvice)
    handlers/      ← RateLimitHandler (suspend fun check)
    requests/      ← RateLimitRequest (DTO + validation)
    responses/     ← RateLimitResponse (DTO)
    routing/       ← RateLimiterRouter (coRouter), RateLimiterRouterOperations (@RouterOperations)
  RateLimiterConfig.kt  ← @Configuration — only wiring point, no logic
```

**Hard boundaries (enforced by PreToolUse hook and detekt):**
- `core/domain` and `core/port`: **zero** Spring/Jakarta imports
- `infra/`: no `@Component`, no `@Autowired` — instantiated manually in `RateLimiterConfig`
- All framework wiring lives exclusively in `RateLimiterConfig.kt`

---

## Algorithm

**Token Bucket with lazy refill and CAS-based thread safety.**

- State stored as `milliTokens: Long` (1 token = 1000 milliTokens) — integer arithmetic,
  no floating point
- Refill computed on every `tryConsume()` call from elapsed wall time — no background threads
- `maxOf(0L, clock.nowMillis() - state.lastRefillAt)` guards against NTP clock regression
- CAS loop on `AtomicReference<BucketState>` prevents TOCTOU races
- `InMemoryBucketStore` uses `ConcurrentHashMap.computeIfAbsent`

---

## Web Layer Convention

**WebFlux Functional API with Kotlin Coroutines — NOT `@RestController`.**

```kotlin
// Router: routing only, zero logic
fun rateLimitRouter(handler: RateLimitHandler) = coRouter {
    POST("/api/rate-limit/check", handler::check)
}

// Handler: thin — validates, delegates, maps status
class RateLimitHandler(private val rateLimiter: RateLimiterPort, private val validator: Validator) {
    suspend fun check(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<RateLimitRequest>()
        // validate → delegate → on Denied: throw RateLimitExceededException
    }
}

// Exception handler: all HTTP 429 mapping lives here, not in the handler
@RestControllerAdvice
class RateLimitExceptionHandler {
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceeded(ex: RateLimitExceededException): ResponseEntity<RateLimitResponse>
}
```

Rules:
- Handlers are `suspend fun` — always
- Denied branch throws — never builds a 429 response directly in the handler
- Handlers contain NO business logic

---

## Testing Strategy

| Test class | What it covers |
|---|---|
| `TokenBucketRateLimiterTest` | Algorithm: capacity, refill, clock regression, partial refill, capping |
| `TokenBucketConcurrencyTest` | CAS correctness: 100 threads on capacity-10 bucket → exactly 10 allowed |
| `InMemoryBucketStoreTest` | Key isolation |
| `RateLimitHandlerTest` | HTTP contracts: 200 / 429 + Retry-After / 400 validation |

HTTP contracts:
- Allowed → 200 OK + `{"allowed":true}`
- Denied → 429 Too Many Requests + `Retry-After: <N>` header + `{"allowed":false}`
- Invalid body → 400 Bad Request

TDD order: failing test first, then minimum implementation to pass.

---

## Build & Quality Gates

```bash
./gradlew build          # compile + test + ktlint + detekt + jacoco (must be green)
./gradlew ktlintFormat   # auto-fix formatting before committing
./gradlew detekt         # static analysis
./gradlew jacocoTestReport  # coverage report at build/reports/jacoco/
```

Coverage minimum: 80% line coverage on `core` and `infra` packages (JaCoCo enforced).

Stack versions:
- Kotlin 2.2.21 / Spring Boot 4.0.3 / Java toolchain 24
- ktlint 1.5.0, detekt 2.0.0-alpha.1, JaCoCo 0.8.13

Known quirks:
- Detekt pinned to Kotlin 2.2.20 in its own config (version mismatch with Spring DM)
- JaCoCo requires 0.8.13 for Java 24 class files
- ktlint requires 1.5.0 for Kotlin 2.x

---

## Code Style (Non-Negotiable)

- No AI slop: no `@param`/`@return` javadoc on obvious getters, no comments that restate
  the code. Add a comment only when the WHY is not obvious from the names.
- No overengineering: solve what is asked, not what might be asked later. Three similar
  lines is better than a premature abstraction.
- No backwards-compatibility hacks: if something is unused, delete it.
- SOLID, DRY, KISS, YAGNI — in that priority order.

---

## OpenAPI / SpringDoc Convention

SpringDoc cannot introspect functional `coRouter` routes. All metadata is declared via a
composed annotation on the `@Bean` method in `RateLimiterConfig`:

```kotlin
@Bean
@RateLimiterRouterOperations   // ← composed annotation in adapter/api/routing/
fun rateLimitRouter(handler: RateLimitHandler) = buildRateLimitRouter(handler)
```

`RateLimiterRouterOperations.kt` in `adapter/api/routing/` is the reference implementation.

---

## Git Conventions

- Atomic commits, imperative mood
- Prefix: `fix/` · `feature/` · `enhancement/` · `refactor/`
- Commit format: `type: short declarative statement`
- One logical change per PR

---

## Observability

- OpenTelemetry → Prometheus → Grafana LGTM stack (`compose.yaml`)
- Log pattern includes `traceId=%X{traceId} spanId=%X{spanId}` (ThreadContext / MDC)
- `docker compose up` starts app (`:8080`) + Grafana (`:3000`) + Prometheus (`:9090`)
