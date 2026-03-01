# Testing Strategy

## TDD Workflow

Every increment follows **RED → GREEN → REFACTOR**:

1. **RED** — write the failing test(s) first, commit
2. **GREEN** — write the minimum production code to make tests pass
3. **REFACTOR** — clean up while keeping tests green; run `./gradlew build` to confirm all gates pass

This order is enforced by convention: the failing test exists in git before the production code that makes it pass.

---

## Test Pyramid

```
          ┌────────────────────────┐
          │  Smoke / Integration   │  1 test class
          │  @SpringBootTest       │  SdImplementationChallengeApplicationTests
          │  RANDOM_PORT           │
          ├────────────────────────┤
          │  HTTP Contract         │  1 test class
          │  @SpringBootTest       │  RateLimitHandlerTest
          │  @MockkBean            │  (full context, mock algorithm)
          ├────────────────────────┤
          │  Algorithm / Infra     │  4 test classes
          │  Unit tests            │  TokenBucketRateLimiterTest
          │  No Spring context     │  TokenBucketConcurrencyTest
          │                        │  InMemoryBucketStoreTest
          ├────────────────────────┤
          │  Domain types          │  2 test classes
          │  Pure Kotlin           │  RateLimitKeyTest
          │                        │  RateLimitResultTest
          └────────────────────────┘
```

---

## Test Classes

### Domain Layer (pure unit)

**`RateLimitKeyTest`** — value class equality, hash contract, map usability.

**`RateLimitResultTest`** — `Allowed` singleton identity, `Denied` field access, compile-time `when` exhaustiveness (no `else` branch required).

Both use **AssertK** (`assertThat(...).isEqualTo(...)`) — Kotlin-native assertions with full type inference.

---

### Infra Layer (unit, no Spring)

**`InMemoryBucketStoreTest`** — three cases:
1. New key → `initial` lambda invoked once
2. Existing key → same `AtomicReference` returned, lambda not called again
3. Two keys → distinct `AtomicReference` instances

**`TokenBucketRateLimiterTest`** — 10 algorithm correctness cases + parameterized sub-token scenarios:

| Test | What it validates |
|---|---|
| First request allowed | Fresh bucket is full |
| Exactly capacity → denied | Bucket exhaustion boundary |
| `Denied` carries `retryAfterSeconds ≥ 1` | Header value is non-zero |
| Refill after 200ms | One token earned at 5/sec |
| Sub-token partial refill (`@ParameterizedTest`) | 100ms/199ms → still denied, 200ms → allowed |
| Refill capped at capacity | No token overflow |
| Frozen clock → denied stays denied | Zero elapsed time = zero refill |
| Two keys are independent | No state bleed between keys |
| Initial state = full bucket | Fresh key starts at capacity |
| `retryAfterSeconds` ceiling | Correct ceiling division |

Uses an injectable `Clock { fixedTime }` stub for deterministic time control. No `Thread.sleep()`, no real-time dependencies.

**`TokenBucketConcurrencyTest`** — race-condition regression test:
- 100 threads held at `CountDownLatch(1)`, released simultaneously
- Capacity-10 bucket must allow exactly 10, deny 90 — every run
- `@RepeatedTest(10)` drives the scenario 10 times to surface non-deterministic failures
- Without the CAS loop (naive `ref.set()`), `allowed > 10` non-deterministically

---

### HTTP Layer (`@SpringBootTest(RANDOM_PORT)`)

**`RateLimitHandlerTest`** — four contract tests with `@MockkBean` for the algorithm:

| Test | Validates |
|---|---|
| Allowed → 200 `{"allowed":true}` | Happy path HTTP mapping |
| Denied → 429 + `Retry-After` + `{"allowed":false}` | Exception handler mapping |
| Missing key field → 400 | Bean validation (null/missing) |
| Blank key `""` → 400 | Bean validation (`@NotBlank`) |

`@SpringBootTest(RANDOM_PORT)` activates the full application context including `@RestControllerAdvice` — the exception handler cannot be tested in standalone mode since `bindToRouterFunction()` does not invoke `@ControllerAdvice`.

---

### Integration / Smoke

**`SdImplementationChallengeApplicationTests`**:
- `contextLoads()` — Spring context must start without exceptions
- `fresh key returns 200 allowed` — end-to-end real HTTP call against the running server, unique key via `UUID.randomUUID()` to avoid cross-test interference

---

## Coverage

JaCoCo enforces **≥ 80% line coverage** on `com/iol/ratelimiter/core/*` and `com/iol/ratelimiter/infra/*`. Strategy:

- Domain tests → near 100% on `core/domain`
- Algorithm tests (10 cases) cover all branches in `TokenBucketRateLimiter` including refill, denial, and `retryAfterSeconds` paths
- Concurrency test covers the CAS retry branch (rarely exercised by sequential tests)
- `adapter/api/` excluded — covered by correctness, not a numeric threshold

Generate the HTML report: `./gradlew jacocoTestReport` → `build/reports/jacoco/test/html/index.html`
