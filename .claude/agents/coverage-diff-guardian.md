# coverage-diff-guardian

Parse the JaCoCo XML report and flag files with insufficient line coverage. Invoked after each increment or before opening a PR to catch silent coverage regressions.

---

## Coverage Thresholds

| Level | Threshold | Action |
|-------|-----------|--------|
| Warning | < 90% line coverage | Report with `⚠` |
| Error | < 80% line coverage | Report with `✗` and FAIL the check |

The project's JaCoCo rule enforces 80% on `com/iol/ratelimiter/core/*` and `com/iol/ratelimiter/infra/*`. This agent surfaces per-file data that the aggregate rule hides.

---

## Steps

### 1. Generate the report

```bash
./gradlew jacocoTestReport
```

Report written to: `build/reports/jacoco/test/jacocoTestReport.xml`

### 2. Parse per-file coverage

Parse `jacocoTestReport.xml`. For each `<sourcefile>` node, compute:

```
linesCovered   = sum of <line covered="..."> where covered > 0
linesTotal     = count of all <line> elements
coverageRatio  = linesCovered / linesTotal
```

Alternatively, read the `COUNTER type="LINE"` element on each `<sourcefile>`:
- `missed` = lines not covered
- `covered` = lines covered
- `ratio = covered / (missed + covered)`

### 3. Filter to guarded packages

Only report on files under:
- `com/iol/ratelimiter/core/`
- `com/iol/ratelimiter/infra/`

Ignore `adapter/api/` — covered by integration tests, excluded from the 80% rule.

### 4. Output report

```
Coverage Report — build/reports/jacoco/test/jacocoTestReport.xml
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✓  core/domain/RateLimitKey.kt          100%  (8/8)
✓  core/domain/BucketState.kt           100%  (4/4)
✓  core/domain/RateLimitResult.kt        96%  (25/26)
⚠  infra/TokenBucketRateLimiter.kt       85%  (34/40)  [below 90% warning]
✗  infra/SomeNewFile.kt                  72%  (18/25)  [below 80% — FAIL]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Result: FAIL — 1 file(s) below 80% threshold
```

If all files ≥ 80%:
```
Result: PASS — all guarded files meet 80% minimum
```

### 5. Suggest fixes for failing files

For each `✗` file, list the uncovered line numbers (from `<line mi="1">` in the XML). This helps pinpoint missing test cases without opening the report HTML.

---

## How to Invoke

```
Use the coverage-diff-guardian agent to check coverage after the last increment.
```

Or as part of the quality gate workflow:
```bash
./gradlew jacocoTestReport
# then invoke coverage-diff-guardian
```

---

## Relationship to JaCoCo Rule

The JaCoCo `jacocoTestCoverageVerification` task in `build.gradle.kts` enforces 80% at the **package** level (aggregate). This agent works at the **file** level — it catches cases where one well-tested file masks a poorly-tested new file in the same package.
