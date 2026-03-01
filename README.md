# Rate Limiter — System Design Implementation Challenge

A working prototype of a **Token Bucket rate limiter**, built as a submission for the System Design Implementation Challenge based on *System Design Interview Vol. 1* by Alex Xu.

---

## What It Does

`POST /api/rate-limit/check` consumes one token from the bucket identified by the request `key`. The bucket refills continuously at the configured rate. When the bucket is empty the request is denied with HTTP 429 and a `Retry-After` header.

**Default config:** 10 tokens capacity, 5 tokens/sec refill (configurable via `application.yaml`).

```bash
# Allowed
curl -X POST http://localhost:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"user-1"}'
# → 200 {"allowed":true}

# Denied (after 10 requests exhaust the bucket)
# → 429 {"allowed":false}  Retry-After: 1
```

---

## Architecture

Hexagonal (ports-and-adapters) in a single Gradle module, 4 logical layers:

```
com.iol.ratelimiter/
  core/domain/     ← pure domain: RateLimitKey, BucketState, RateLimitResult, TokenBucketConfig
  core/port/       ← interfaces: Clock, BucketStore, RateLimiterPort
  infra/           ← implementations: SystemClock, InMemoryBucketStore, TokenBucketRateLimiter
  adapter/api/     ← WebFlux Functional router (coRouter) + thin handler + DTOs + exception handler
  RateLimiterConfig.kt  ← @Configuration wiring all beans (zero @Component in infra/adapter)
  OpenApiConfig.kt      ← SpringDoc OpenAPI metadata
```

**Algorithm:** Token Bucket with lazy refill. State stored as `milliTokens` (Long) for exact integer CAS — 1 token = 1000 milliTokens. Refill computed on each `tryConsume()` from elapsed time; no background threads.

**Thread safety:** `AtomicReference<BucketState>` + CAS loop. See [`rate-limiter/DESIGN.md`](rate-limiter/DESIGN.md) for the full rationale.

---

## Build & Run

### Prerequisites

- Java 24 (Temurin recommended)
- Docker (for local observability stack)

### Commands

```bash
./gradlew build              # compile + ktlint + detekt + test + JaCoCo verification
./gradlew test               # run all tests
./gradlew ktlintFormat       # auto-fix lint violations
./gradlew jacocoTestReport   # generate HTML coverage report → build/reports/jacoco/
./gradlew bootRun            # run locally (starts Grafana LGTM stack via Docker Compose)
```

---

## Docker

Start the app + full observability stack with one command:

```bash
docker compose up
```

| Service | URL |
|---------|-----|
| App | http://localhost:8080 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

See [`docs/deployment.md`](docs/deployment.md) for AWS EC2 free-tier deployment instructions.

---

## Quality Gates

All gates are enforced by `./gradlew build` via `tasks.check`:

| Gate | Tool | Threshold |
|---|---|---|
| Code style | ktlint 1.5.0 | Zero violations |
| Static analysis | detekt 2.0.0-alpha.1 | Zero issues |
| Line coverage | JaCoCo 0.8.13 | ≥ 80% on `core/` + `infra/` packages |
| Architecture | `core/` has zero Spring imports | Verified by `architecture-guardian` agent |

---

## API Documentation

SpringDoc OpenAPI is auto-configured. Once the app is running:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI JSON:** http://localhost:8080/v3/api-docs

---

## Observability

The app ships with a `compose.yaml` that starts:

- **Grafana** — http://localhost:3000 (dashboards)
- **Prometheus** — http://localhost:9090 (metrics scraping)
- **OpenTelemetry Collector** — traces forwarded from the app

Traces include `traceId` and `spanId` in every log line via the Log4j2 pattern (`log4j2-spring.xml`).

---

## Testing Strategy

| Layer | Test class | Type |
|---|---|---|
| Domain types | `RateLimitKeyTest`, `RateLimitResultTest` | Pure unit |
| Algorithm | `TokenBucketRateLimiterTest` | Unit (injectable Clock) |
| Store isolation | `InMemoryBucketStoreTest` | Unit |
| Concurrency | `TokenBucketConcurrencyTest` | Race test (`@RepeatedTest(10)`, 100 threads) |
| HTTP contract | `RateLimitHandlerTest` | `@SpringBootTest(RANDOM_PORT)` + `@MockkBean` |
| Smoke | `SdImplementationChallengeApplicationTests` | Full integration |

See [`docs/testing.md`](docs/testing.md) for the full TDD approach and test pyramid rationale.

---

## Design Document

[`rate-limiter/DESIGN.md`](rate-limiter/DESIGN.md) — required submission artifact. Covers algorithm choice, thread-safety model, key design decisions, trade-offs, and AI usage.

---

## Stack

- Kotlin 2.2.21 + Spring Boot 4.0.3 + WebFlux functional (`coRouter` + Coroutines)
- Java toolchain: 24
- Gradle 9.3.1 with Kotlin DSL
- ktlint 1.5.0 · detekt 2.0.0-alpha.1 · JaCoCo 0.8.13
- Test: JUnit 5 · MockK · AssertK · WebTestClient
- Observability: OpenTelemetry + Prometheus + Grafana LGTM
- SpringDoc OpenAPI 3.0.1 — Swagger UI at `/swagger-ui.html`
