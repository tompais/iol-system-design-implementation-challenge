# Development Guide

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 24 (Temurin) | Kotlin 2.x does not support Java 25 |
| Docker | Any recent | Required for local observability stack |
| SDKMAN (optional) | — | `sdk install java 24.0.2-tem` |

---

## Running Locally

```bash
./gradlew bootRun
```

This starts the app on port 8080 **and** launches the Grafana LGTM stack via Docker Compose (`compose.yaml`):

| Service | URL |
|---|---|
| App | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

---

## Smoke Test

```bash
# First request on a fresh key — always allowed
curl -X POST http://localhost:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"user-1"}'
# → 200 {"allowed":true}

# Exhaust the bucket (run 10 times for default capacity=10)
for i in $(seq 1 11); do
  curl -s -X POST http://localhost:8080/api/rate-limit/check \
    -H 'Content-Type: application/json' \
    -d '{"key":"burst-test"}' | jq .allowed
done
# → true (×10) then false (×1 with Retry-After header)
```

---

## Quality Gates

All gates run via `./gradlew build`. Each can also be run independently:

```bash
./gradlew ktlintCheck          # code style — zero violations required
./gradlew ktlintFormat         # auto-fix style violations
./gradlew detekt               # static analysis — zero issues required
./gradlew test                 # all tests (unit + integration)
./gradlew jacocoTestReport     # coverage HTML → build/reports/jacoco/test/html/index.html
./gradlew jacocoTestCoverageVerification  # enforce ≥ 80% on core/ + infra/
```

### Coverage Rule

JaCoCo enforces **80% line coverage** on `com/iol/ratelimiter/core/*` and `com/iol/ratelimiter/infra/*`. The `adapter/api/` package is excluded from the coverage rule — it is covered by correctness (handler + smoke tests), not by a numeric threshold.

---

## Active Automations (Claude Code Hooks)

| Automation | Trigger | Effect |
|---|---|---|
| `ktlint-format-on-save.sh` | PostToolUse on `.kt` edit | Runs `./gradlew ktlintFormat` |
| `detekt-on-save.sh` | PostToolUse on `.kt` edit | Runs `./gradlew detektMain` |
| `guard-build-files.sh` | PreToolUse on Edit/Write | Warns before editing `build.gradle.kts` |

---

## Known Build Quirks

- **Detekt Kotlin version:** `dev.detekt:2.0.0-alpha.1` compiled with Kotlin 2.2.20. Spring DM upgrades `kotlin-compiler-embeddable` to 2.2.21. Fixed via `configurations.matching { it.name.startsWith("detekt") }` pinning in `build.gradle.kts`.
- **JaCoCo Java 24:** requires `toolVersion = "0.8.13"`. Earlier versions don't support class file major version 68.
- **ktlint Kotlin 2.x:** requires `version = "1.5.0"`. Earlier ktlint versions reference `HEADER_KEYWORD` removed from Kotlin 2.x.
- **Log4j2 conflict:** `spring-boot-starter-logging` pulls in `log4j-to-slf4j` which conflicts with `log4j-slf4j2-impl`. Both are excluded — see `build.gradle.kts` `configurations.all` block.
- **Line length:** `.editorconfig` and `detekt.yml` both set `max_line_length = 130` to keep ktlint and detekt aligned.

---

## Gradle Properties

All dependency and plugin versions live in `gradle.properties` — no hardcoded version strings in `build.gradle.kts`. To upgrade a dependency, change the property and run `./gradlew build`.

```properties
# Key versions
kotlinVersion=2.2.21
springBootVersion=4.0.3
ktlintPluginVersion=12.2.0
detektPluginVersion=2.0.0-alpha.1
jacocoToolVersion=0.8.13
```
