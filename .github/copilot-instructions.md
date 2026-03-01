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
  core/domain/     ← pure Kotlin: RateLimitKey, BucketState, RateLimitDeniedException, TokenBucketConfig
  core/port/       ← interfaces only: Clock, BucketStore, RateLimiterPort
  infra/           ← implementations: SystemClock, InMemoryBucketStore, TokenBucketRateLimiter
  adapter/api/
    errors/
      exceptions/  ← BadRequestException
      handler/     ← RateLimitExceptionHandler (@RestControllerAdvice)
    handlers/      ← RateLimitHandler (suspend fun check)
    requests/      ← RateLimitRequest (DTO + validation)
    responses/     ← RateLimitResponse (DTO)
    routing/
      routers/     ← RateLimiterRouter (coRouter)
      routers/operations/annotations/ ← RateLimiterRouterOperations (@RouterOperations)
    validation/    ← BodyValidator
  RateLimiterConfig.kt  ← @Configuration — only wiring point, no logic
```

**Hard boundaries (enforced by PreToolUse hook and detekt):**
- `core/domain` and `core/port`: **zero** Spring/Jakarta imports
- `infra/`: no `@Component`, no `@Autowired` — instantiated manually in `RateLimiterConfig`
- All framework wiring lives exclusively in `RateLimiterConfig.kt`

---

## Engineering Principles & Methodology

### TDD — Test-Driven Development (mandatory)

Every change follows **Red → Green → Refactor**:

1. **Red**: write a failing test that describes the desired behaviour
2. **Green**: write the minimum production code to make it pass
3. **Refactor**: clean up without changing behaviour, keeping tests green

**Order of tests:**
- Core logic unit tests (no Spring, stub `Clock`) before the implementation
- Integration tests (`@SpringBootTest`) before edge-case unit tests
- Concurrency tests (`@RepeatedTest` + `CountDownLatch`) to validate thread safety

Never write production code without a failing test that justifies it.
Never skip the refactor step — that is where clean code is born.

---

### SOLID

| Principle | How it applies here |
|---|---|
| **S** — Single Responsibility | Each class has exactly one reason to change. `RateLimitHandler` maps HTTP. `TokenBucketRateLimiter` runs the algorithm. `RateLimiterConfig` wires beans. Never mix these. |
| **O** — Open/Closed | Add behaviour by creating new implementations of `RateLimiterPort` or `BucketStore`, not by modifying existing ones. |
| **L** — Liskov Substitution | Any `BucketStore` implementation must honour the contract defined by the port. `InMemoryBucketStore` is replaceable without changing `TokenBucketRateLimiter`. |
| **I** — Interface Segregation | `Clock`, `BucketStore`, and `RateLimiterPort` are intentionally small and focused. Do not add methods to a port that only one consumer needs. |
| **D** — Dependency Inversion | `core/` depends on abstractions (its own port interfaces). `infra/` depends on `core/port`. No layer depends on a concrete class from an outer layer. |

---

### DRY — Don't Repeat Yourself

- Extract a concept only when it appears **three or more times** with the same intent
- Do not create helpers or utilities for one-time operations
- Shared logic belongs in the domain or a port, not in a utility class

---

### KISS — Keep It Simple, Stupid

- The simplest solution that makes the tests pass is the right solution
- Prefer explicit code over clever abstractions
- Avoid nested lambdas, operator overloading, or DSL construction unless the codebase already uses them
- If you need a comment to explain what the code does, the code is not simple enough

---

### YAGNI — You Aren't Gonna Need It

- Do not implement features that are not explicitly required
- No feature flags, no configuration options "just in case", no backwards-compatibility shims
- Do not design for hypothetical future requirements — solve what is asked, not what might be asked later
- Three similar lines of code is better than a premature abstraction

---

### DDD — Domain-Driven Design (tactical patterns in use)

- **Value Object**: `RateLimitKey` is a `@JvmInline value class` — identity is its value, not a reference
- **Domain exception**: `RateLimitDeniedException` carries `retryAfterSeconds` — the port throws on denial instead of returning a result type; the adapter catches it in `RateLimitExceptionHandler`
- **Ubiquitous language**: class and method names come from the domain (`tryConsume`, `BucketState`, `milliTokens`, `refillRatePerSecond`) — never from technical concerns (`doProcess`, `handleData`)
- **Rich domain objects**: `BucketState` encapsulates token arithmetic; it is not a plain data bag
- Domain objects live in `core/domain`. They depend on nothing outside that package.

---

### Hexagonal Architecture — The Dependency Rule

```
     ┌──────────────────────────────────────┐
     │              core/domain              │  ← no dependencies
     │  RateLimitKey · BucketState · Config   │
     └──────────────┬───────────────────────┘
                    │ used by
     ┌──────────────▼───────────────────────┐
     │              core/port                │  ← depends only on domain
     │  Clock · BucketStore · RateLimiterPort│
     └──────┬─────────────────────┬─────────┘
            │ implemented by      │ used by
   ┌────────▼──────────┐  ┌───────▼─────────────────────┐
   │       infra/      │  │       adapter/api/           │
   │ TokenBucketRate-  │  │  RateLimitHandler · Router  │
   │   Limiter         │  │  BodyValidator               │
   │                   │  │  RateLimitExceptionHandler   │
   │ InMemoryBucket-   │  └─────────────────────────────┘
   │   Store           │
   │ SystemClock       │
   └───────────────────┘
```

- `core/` must compile with zero Spring/Jakarta imports — enforced by a PreToolUse hook
- `infra/` has no `@Component` or `@Autowired` — all wiring is explicit in `RateLimiterConfig`
- The adapter knows about ports; it does NOT know about `infra/` concrete classes

---

### Clean Code

- **Names**: classes, functions, and variables must read like prose. No abbreviations, no
  single-letter variables outside of well-known idioms (`i` in a loop, `e` in a catch)
- **Function size**: a function that doesn't fit on a screen is doing too much
- **Comments**: only the **WHY**, never the **WHAT**. If the code needs a comment to explain
  what it does, rename or refactor until it doesn't
- **No dead code**: remove unused variables, functions, and imports immediately
- **No commented-out code**: use git history instead
- **No boilerplate KDoc**: no `@param`/`@return` on self-documenting methods. No docstrings
  that restate the class name

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

// Handler: 5 lines of linear logic — validate, delegate, return 200
class RateLimitHandler(private val rateLimiter: RateLimiterPort, private val bodyValidator: BodyValidator) {
    suspend fun check(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<RateLimitRequest>()
        bodyValidator.validate(body)          // throws BadRequestException on violation
        rateLimiter.tryConsume(RateLimitKey(body.key))  // throws RateLimitDeniedException on denial
        return ServerResponse.ok().bodyValueAndAwait(RateLimitResponse(true))
    }
}

// Exception handler: HTTP 429 / 400 mapping lives here, not in the handler
@RestControllerAdvice
class RateLimitExceptionHandler {
    @ExceptionHandler(RateLimitDeniedException::class)
    fun handleRateLimitDenied(ex: RateLimitDeniedException): ResponseEntity<RateLimitResponse>

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<List<String>>
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

- **Use KDoc (`/** */`), never JavaDoc.** KDoc is the Kotlin standard. Omit `@param`/`@return`
  tags on self-documenting methods — they add noise, not value.
- No AI slop: no `@param`/`@return` on obvious getters, no comments that restate
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

`RateLimiterRouterOperations.kt` in `adapter/api/routing/routers/operations/annotations/` is the reference implementation.

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
