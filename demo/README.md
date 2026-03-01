# Live Demo — k6 Load Test

A [k6](https://k6.io) script that exercises the rate limiter end-to-end with five labelled
scenarios. Each scenario has a threshold — the script exits non-zero if any check fails.

## Prerequisites

1. App running on `http://localhost:8080` (either via Docker or Gradle):
   ```bash
   docker compose up        # starts app + Grafana LGTM stack
   # or
   ./gradlew bootRun
   ```

2. k6 installed:
   ```bash
   brew install k6          # macOS
   # or see https://k6.io/docs/getting-started/installation/
   ```

## Run

```bash
k6 run demo/rate-limiter-demo.js
```

Override the base URL if needed:

```bash
BASE_URL=http://my-server:8080 k6 run demo/rate-limiter-demo.js
```

## Scenarios

| Scenario | What it tests | Expected |
|----------|--------------|----------|
| `single_allowed` | Fresh UUID key | 200 `{"allowed":true}` |
| `bucket_exhaustion` | 10 requests same key → 11th | first 10: 200 · 11th: 429 + `Retry-After` |
| `validation_missing_key` | POST `{}` | 400 |
| `validation_blank_key` | POST `{"key":""}` | 400 |
| `concurrency` | 100 VUs same key simultaneously | 10 allowed + 90 denied |

## Expected Output

```
✓ single allowed → 200
✓ single allowed → allowed=true
✓ request 1 allowed → 200
...
✓ exhausted bucket → 429
✓ exhausted bucket → allowed=false
✓ exhausted bucket → Retry-After present
✓ missing key → 400
✓ blank key → 400
✓ burst allowed → allowed=true (×10)
✓ burst denied → 429 (×90)

Concurrency burst: 10 allowed / 90 denied (capacity=10)
```

All checks at `rate==1`, `concurrency_allowed count==10`, `concurrency_denied count==90` → exit code 0.
