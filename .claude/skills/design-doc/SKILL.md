---
name: design-doc
description: Update rate-limiter/DESIGN.md to reflect current architectural decisions, algorithm trade-offs, and AI usage notes. Call after significant implementation changes or before submitting.
---

Read the current implementation in `src/main/kotlin/com/iol/ratelimiter/` and update `rate-limiter/DESIGN.md` to accurately reflect:

1. **Algorithm choice** — Why Token Bucket, and what was explicitly rejected (e.g. Sliding Window Counter) and why
2. **Thread-safety model** — Explain the CAS loop on `AtomicReference<BucketState>` and the real scenario it protects against
3. **Key design decisions** — `milliTokens` integer representation, lazy refill, clock injection for testability
4. **Trade-offs** — What this prototype does NOT do (distributed storage, Redis backend, sliding window) and why that's the right scope
5. **AI usage** — How Claude Code was used: planning, TDD, code generation, what was reviewed/understood by the developer

Keep each section concise (1-2 paragraphs). This file is a required submission artifact evaluated by the interviewer.
