---
name: quality-gate
description: Run the full quality gate for this project (tests + detekt). Use before merging any branch or after completing an increment.
disable-model-invocation: true
---

Run the following command from the project root and report results clearly:

```bash
./gradlew test detekt
```

Report:
- Number of tests passed / failed / skipped
- Any Detekt violations (file, line, rule name)
- Overall pass/fail for each tool
- Next recommended action if anything failed
