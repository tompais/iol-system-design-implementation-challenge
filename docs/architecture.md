# Architecture

## Overview

The rate limiter follows **Hexagonal Architecture** (ports and adapters), organized as a single Gradle module with four logical layers. The dependency rule flows strictly inward: adapters depend on ports, ports depend on domain, domain depends on nothing.

```
┌─────────────────────────────────────────────────┐
│  adapter/api        WebFlux Functional            │
│  (HTTP in/out)      coRouter + handler + DTOs     │
├─────────────────────────────────────────────────┤
│  core/port          Interfaces                    │
│  (contract)         Clock, BucketStore, Port      │
├─────────────────────────────────────────────────┤
│  core/domain        Pure Kotlin types             │
│  (business)         RateLimitKey, BucketState,    │
│                     RateLimitDeniedException,      │
│                     TokenBucketConfig              │
├─────────────────────────────────────────────────┤
│  infra/             Implementations               │
│  (tech detail)      SystemClock, InMemoryStore,   │
│                     TokenBucketRateLimiter         │
└─────────────────────────────────────────────────┘
```

See [`diagrams/component.mmd`](../diagrams/component.mmd) for the Mermaid component diagram (renders natively on GitHub). The PlantUML sequence diagram is at [`diagrams/sequence-check.puml`](../diagrams/sequence-check.puml).

---

## Why Hexagonal?

**Testability without a server.** The entire algorithm (`TokenBucketRateLimiter`) runs in unit tests with a fake `Clock` and a real `InMemoryBucketStore` — no Spring context, no HTTP, no randomness. This is possible only because the domain has zero framework dependencies.

**Swappable infrastructure.** `InMemoryBucketStore` could be replaced with a Redis-backed store by implementing the `BucketStore` port. The algorithm, port interfaces, and HTTP adapter would require zero changes. The CAS loop would need to become a Lua script or Redis `WATCH`/`MULTI`/`EXEC` transaction — but that's a single-class change in `infra/`.

**Enforced boundary.** A CI check (`architecture-guardian` subagent) verifies that `core/domain` and `core/port` contain zero Spring/Jakarta imports. This is checked on every PR.

---

## Package Boundaries

| Package | Rule | Verified by |
|---|---|---|
| `core/domain` | Zero imports from Spring, Jakarta, or `infra` | architecture-guardian |
| `core/port` | May import `core/domain` only | architecture-guardian |
| `infra/` | May import `core/` only — no `@Component`/`@Autowired` | code review |
| `adapter/api/` | May import `core/port` and `core/domain` — no `infra` imports | code review |
| `RateLimiterConfig` | Single `@Configuration` class — all wiring in one place | convention |

---

## Web Layer: WebFlux Functional

The HTTP layer uses **WebFlux Functional style** (not `@RestController`). This means:

- **Router** (`RateLimiterRouter.kt`): declares routes only — `coRouter { POST("/path", handler::fn) }`
- **Handler** (`RateLimitHandler.kt`): validates request body, delegates to `RateLimiterPort`, throws on denial
- **Exception Handler** (`RateLimitExceptionHandler.kt`): `@RestControllerAdvice` that maps `RateLimitDeniedException` → 429 + `Retry-After` and `BadRequestException` → 400 — keeps the handler free of HTTP status decisions
- **DTOs** (`RateLimitRequest`, `RateLimitResponse`): data classes at the HTTP boundary only

The router is a `RouterFunction` bean — it can be tested with `WebTestClient.bindToRouterFunction()` (standalone, no server) or via `@SpringBootTest(RANDOM_PORT)` for full context including the exception handler.

---

## Configuration & Wiring

`RateLimiterConfig` (`@Configuration`) is the single source of truth for the dependency graph. All beans are explicitly constructed via `@Bean` factory methods. No `@Autowired` or `@Component` annotations exist inside `infra/` or `adapter/api/` — explicit wiring makes the dependency graph visible and traceable.

`application.yaml` holds the two tuneable parameters:
- `rate-limiter.capacity` — bucket size (tokens)
- `rate-limiter.refill-rate-per-second` — token refill rate
