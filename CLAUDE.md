# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

This is a **System Design Implementation Challenge** — a working prototype of a **Rate Limiter** (Token Bucket algorithm), based on "System Design Interview Vol. 1" by Alex Xu. The challenge requires compiling code, passing tests, clean design, and documented trade-offs. See `CHALLENGE.md` for scoring criteria.

## Engineering Principles (Non-Negotiable)

- **Clean Architecture**: `core/domain` and `core/port` have ZERO Spring imports. All framework dependencies live in `infra/` and `adapter/`.
- **SOLID, DRY, KISS, YAGNI**: No pre-emptive abstractions. No helpers for one-time use. Three similar lines of code is better than a premature abstraction.
- **TDD**: Write the test first. Core logic tests before implementation. Integration tests before edge-case unit tests.
- **No overengineering**: 3–5 modules max, explicit naming, no exotic patterns. Solve what is asked, not what might be asked later.
- **No AI slop**: No boilerplate comments, no `@param`/`@return` javadoc on obvious getters, no docstrings on self-documenting code. Add a comment only when the WHY is not obvious from the code.
- **80% line coverage**: JaCoCo enforces 80% minimum on `core` and `infra` packages. Strategy: integration tests cover the happy path and HTTP contracts; unit tests cover edge cases and concurrency.

## Web Layer: WebFlux Functional API with Coroutines

The web adapter uses **WebFlux Functional style with Kotlin Coroutines**, NOT `@RestController`. This means:

```kotlin
// Router (routing only — no logic)
fun rateLimitRouter(handler: RateLimitHandler) = coRouter {
    POST("/api/rate-limit/check", handler::check)
}

// Handler (thin — delegates to use case, maps HTTP status)
class RateLimitHandler(private val rateLimiter: RateLimiterPort) {
    suspend fun check(request: ServerRequest): ServerResponse {
        val key = request.awaitBody<RateLimitRequest>().key
        return when (val result = rateLimiter.tryConsume(RateLimitKey(key))) {
            is RateLimitResult.Allowed -> ServerResponse.ok().buildAndAwait()
            is RateLimitResult.Denied  -> ServerResponse.status(429)
                .header("Retry-After", ...)
                .buildAndAwait()
        }
    }
}
```

**Handler rules**: handlers know which service to call, which HTTP status to return, and how to resolve the route. They contain NO business logic. Validation lives in the request DTO or service layer.

## Build & Development Commands

```bash
./gradlew build              # compile + test + ktlint + detekt + jacoco
./gradlew test               # run all tests
./gradlew test --tests "com.iol.ratelimiter.core.TokenBucketRateLimiterTest"  # single test class
./gradlew ktlintCheck        # lint check (zero violations required)
./gradlew ktlintFormat       # auto-fix lint issues
./gradlew detekt             # static analysis
./gradlew jacocoTestReport   # generate HTML coverage report (build/reports/jacoco/)
./gradlew bootRun            # run locally (starts Grafana stack via Docker Compose)
```

Smoke test:
```bash
curl -X POST http://localhost:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"user-1"}'
```

## Architecture

Hexagonal (ports-and-adapters) in a single Gradle module, 4 logical layers:

```
com.iol.ratelimiter/
  core/domain/     ← pure domain: RateLimitKey (value class), BucketState, RateLimitResult (sealed), TokenBucketConfig
  core/port/       ← interfaces: Clock (fun interface), BucketStore, RateLimiterPort
  infra/           ← implementations: SystemClock, InMemoryBucketStore (ConcurrentHashMap), TokenBucketRateLimiter (CAS loop)
  adapter/api/     ← WebFlux Functional router (coRouter) + thin handler + DTOs
  RateLimiterConfig.kt  ← @Configuration wiring all beans

com.iol.sdimplementationchallenge/
  SdImplementationChallengeApplication.kt  ← @SpringBootApplication(scanBasePackages = ["com.iol"])
```

**Algorithm**: Token Bucket with lazy refill. State stored as `milliTokens` (Long) to enable integer CAS — 1 token = 1000 milliTokens. Refill is computed on each `tryConsume()` call based on elapsed time; no background threads.

**Thread safety**: `AtomicReference<BucketState>` + CAS loop in `TokenBucketRateLimiter`. `InMemoryBucketStore` uses `ConcurrentHashMap.computeIfAbsent`. Race condition test: 100 concurrent requests on a capacity-10 bucket must allow exactly 10.

## Testing Strategy

- `core/TokenBucketRateLimiterTest.kt` — pure unit tests, no Spring, uses injectable `Clock` stub
- `core/TokenBucketConcurrencyTest.kt` — `@RepeatedTest(10)` with 100-thread `CountDownLatch` to validate CAS correctness
- `infra/InMemoryBucketStoreTest.kt` — verifies key isolation
- `adapter/api/RateLimitHandlerTest.kt` — WebFlux functional test with `@MockkBean`

HTTP contracts: allowed → 200 `{"allowed":true}`; denied → 429 + `Retry-After` header.

## Key Constraints (from CHALLENGE.md)

- **Justify concurrency**: every concurrent construct must trace to a real scenario (race test validates it)
- **TDD order**: failing tests first, then implement
- **3–5 modules max**: domain → port → infra → adapter (no extra layers)
- **Trade-offs documented**: `rate-limiter/DESIGN.md` explains Token Bucket vs alternatives

## Stack

- Kotlin 2.2.21 + Spring Boot 4.0.3 + WebFlux functional (coRouter, handlers, Coroutines)
- Java toolchain: 24 (Kotlin 2.x does not support Java 25)
- Gradle 9.3.1 with Kotlin DSL
- ktlint 1.5.0 (via `org.jlleitschuh.gradle.ktlint:12.2.0`) — formatting/style gate
- detekt 2.0.0-alpha.1 (via `dev.detekt`) — static analysis gate
- JaCoCo 0.8.13 — 80% line coverage on core + infra packages
- Test: JUnit 5, MockK via `com.ninja-squad:springmockk:4.0.2`, WebTestClient
- Observability: OpenTelemetry + Prometheus + Grafana LGTM (`compose.yaml`)
- SpringDoc OpenAPI — Swagger UI at `/swagger-ui.html`

## Documentation Structure

```
README.md              ← how to build/test/run, quality gates, SpringDoc link
rate-limiter/DESIGN.md ← required submission: algorithm, thread-safety, trade-offs, AI usage
docs/
  architecture.md      ← component diagram explanation, hexagonal design rationale
  development.md       ← local setup, quality gates, SonarLint config guidance
  testing.md           ← TDD approach, test pyramid for this project
diagrams/
  component.puml       ← PlantUML component diagram
  sequence-check.puml  ← PlantUML sequence diagram for POST /api/rate-limit/check
```

## Git Conventions

Atomic commits, imperative mood. Branch prefix → purpose:
- `fix/` → bug fixes
- `feature/` → new functionality
- `enhancement/` → improvements to existing features
- `refactor/` → restructuring without behaviour change

Commit format: `type: short declarative statement`

## Claude Code Automations (Active)

| Automation | Type | Trigger | Purpose |
|---|---|---|---|
| `detekt-on-save.sh` | PostToolUse hook | Any `.kt` file edit | Runs `./gradlew detektMain` after every Kotlin edit |
| `guard-build-files.sh` | PreToolUse hook | Edit/Write | Warns before modifying `build.gradle.kts`, `gradle.properties` |
| `/quality-gate` | Skill | User invocation | Runs `./gradlew test detekt` and reports results |
| `/design-doc` | Skill | User or Claude | Updates `rate-limiter/DESIGN.md` from current source |
| context7 | MCP server | Auto (in `.mcp.json`) | Live Spring Boot 4.x / Kotlin / Resilience4J docs |

The Detekt hook only fires once the plugin is configured in `build.gradle.kts`. Gradle daemon keeps subsequent runs fast (~3-5s after warmup).

## Known Build Quirks

- **Detekt Kotlin version**: `dev.detekt:2.0.0-alpha.1` compiled with Kotlin 2.2.20. Spring DM upgrades `kotlin-compiler-embeddable` to 2.2.21 in all configs → version mismatch. Fix: `configurations.matching { it.name.startsWith("detekt") }` pins Kotlin to 2.2.20 for the detekt config only.
- **JaCoCo Java 24**: requires `toolVersion = "0.8.13"`. Earlier versions don't support class file major version 68.
- **ktlint Kotlin 2.x**: requires `version = "1.5.0"` in `ktlint {}` block. Earlier ktlint versions reference `HEADER_KEYWORD` which was removed from `KtTokens` in Kotlin 2.x.
