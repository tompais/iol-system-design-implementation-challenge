# /increment — TDD Increment Workflow

Guide through one complete TDD increment of the Rate Limiter challenge, from branch creation to merged PR.

---

## Step 1 — Determine Increment Number

Ask the user which increment number they are starting (2–6).

**Branch naming map:**

| Increment | Branch |
|-----------|--------|
| 2 | `feature/rate-limiter-domain` |
| 3 | `feature/rate-limiter-algorithm` |
| 4 | `feature/rate-limiter-concurrency` |
| 5 | `feature/rate-limiter-web-adapter` |
| 6 | `feature/ci-and-docs` |

```bash
git checkout master && git pull origin master
git checkout -b <branch-from-map>
```

---

## Step 2 — Write Failing Tests First (RED)

**TDD order is non-negotiable:**
1. Write the test(s) that define the expected behaviour
2. Run them and confirm they FAIL (red):
   ```bash
   ./gradlew test --tests "com.iol.ratelimiter.<TestClassName>"
   ```
3. Do NOT write implementation code until the tests are red

**Architecture reminder — core/domain and core/port have ZERO Spring imports.**
Every import in those packages must be from `kotlin.*`, `java.*`, or other pure-JVM libraries.
No `org.springframework.*`, no `jakarta.*`.

---

## Step 3 — Implement to Green

Write the minimum code to make the failing tests pass.

Run the full quality gate after implementation:
```bash
./gradlew build
```

This single command runs: compile → ktlint → detekt → tests → JaCoCo (80% line coverage on `core` + `infra`).

All gates must pass before proceeding.

---

## Step 4 — Commit

One atomic commit per increment:

```bash
git add <files>
git commit -m "type: short declarative statement"
```

**Commit types:** `feature`, `fix`, `refactor`, `test`, `docs`
**Examples:**
- `feature: implement Token Bucket domain model with milliTokens representation`
- `test: add concurrency race condition test for CAS loop correctness`

---

## Step 5 — Push and Open PR

```bash
git push -u origin <branch>
gh pr create --title "<concise imperative title>" --body "..."
```

**PR body template:**
```
## Summary
- <what changed and why — 2-3 bullets>

## Test plan
- [ ] `./gradlew build` passes
- [ ] <specific test class that validates the increment>
- [ ] <edge case or concurrency scenario if applicable>

🤖 Generated with Claude Code
```

---

## Step 6 — Merge and Return to Master

```bash
gh pr merge <number> --squash --delete-branch
git checkout master && git pull origin master
```

---

## Quality Checklist (before PR is opened)

- [ ] Tests written BEFORE implementation (TDD)
- [ ] `./gradlew build` passes (all gates green)
- [ ] `core/domain` and `core/port` have zero Spring/Jakarta imports
- [ ] No business logic in `adapter/` layer
- [ ] No premature abstractions (YAGNI)
- [ ] No `@param`/`@return` javadoc on obvious getters
