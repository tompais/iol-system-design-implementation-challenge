# architecture-guardian

Verify that Clean Architecture boundaries are respected. The `core/` and `infra/` packages must have zero Spring or Jakarta imports.

---

## Rule

`core/domain`, `core/port`, and `infra/` are framework-free zones. They may only import from:
- `kotlin.*`
- `java.*`
- Other pure-JVM / pure-Kotlin libraries

Violations must be moved to `adapter/` or wired through `RateLimiterConfig.kt`.

---

## Check Commands

Run these grep commands from the project root. Zero output = PASS.

```bash
# Check for Spring imports in core/
grep -r "import org.springframework" \
  src/main/kotlin/com/iol/ratelimiter/core/ \
  --include="*.kt" -l

# Check for Jakarta imports in core/
grep -r "import jakarta" \
  src/main/kotlin/com/iol/ratelimiter/core/ \
  --include="*.kt" -l

# Check for Spring imports in infra/
grep -r "import org.springframework" \
  src/main/kotlin/com/iol/ratelimiter/infra/ \
  --include="*.kt" -l

# Check for Jakarta imports in infra/
grep -r "import jakarta" \
  src/main/kotlin/com/iol/ratelimiter/infra/ \
  --include="*.kt" -l
```

---

## Report Format

After running all four commands, output one of:

**PASS — Architecture boundaries clean**
```
✓ core/   — 0 Spring imports, 0 Jakarta imports
✓ infra/  — 0 Spring imports, 0 Jakarta imports
```

**FAIL — Boundary violation(s) detected**
```
✗ core/domain/Foo.kt — imports org.springframework.stereotype.Component
  → Move annotation to RateLimiterConfig.kt or adapter/ layer

✗ infra/Bar.kt — imports jakarta.inject.Inject
  → Replace with constructor injection wired in RateLimiterConfig.kt
```

---

## How to Invoke

From a Claude Code session:

```
Use the architecture-guardian agent to check boundary violations.
```

Or in Loki Mode, include a VERIFY step:

```bash
# Run as part of CI-style check
grep -r "import org.springframework" src/main/kotlin/com/iol/ratelimiter/core/ --include="*.kt" -l
grep -r "import jakarta" src/main/kotlin/com/iol/ratelimiter/core/ --include="*.kt" -l
grep -r "import org.springframework" src/main/kotlin/com/iol/ratelimiter/infra/ --include="*.kt" -l
grep -r "import jakarta" src/main/kotlin/com/iol/ratelimiter/infra/ --include="*.kt" -l
```

Zero lines of output = PASS. Any file path = FAIL, fix before merging.
