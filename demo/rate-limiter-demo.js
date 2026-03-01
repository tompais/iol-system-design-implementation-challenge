/**
 * k6 demo script for the Token Bucket rate limiter.
 *
 * Prerequisites: app running on http://localhost:8080
 *   docker compose up   (or ./gradlew bootRun)
 *
 * Run:
 *   k6 run demo/rate-limiter-demo.js
 *
 * Exits 0 if all checks pass; non-zero on any failure.
 *
 * Default config: capacity=10, refillRatePerSecond=5 (see application.yaml)
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const ENDPOINT = `${BASE_URL}/api/rate-limit/check`;
const HEADERS = { "Content-Type": "application/json" };
const CAPACITY = 10;

export const options = {
  scenarios: {
    single_allowed: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      exec: "singleAllowed",
      startTime: "0s",
    },
    bucket_exhaustion: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      exec: "bucketExhaustion",
      startTime: "1s",
    },
    validation_missing_key: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      exec: "validationMissingKey",
      startTime: "3s",
    },
    validation_blank_key: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      exec: "validationBlankKey",
      startTime: "4s",
    },
    concurrency: {
      executor: "shared-iterations",
      vus: 100,
      iterations: 100,
      exec: "concurrencyBurst",
      startTime: "5s",
      maxDuration: "15s",
    },
  },
  thresholds: {
    // All named checks must pass 100% of the time
    "checks{scenario:single_allowed}": ["rate==1"],
    "checks{scenario:bucket_exhaustion}": ["rate==1"],
    "checks{scenario:validation_missing_key}": ["rate==1"],
    "checks{scenario:validation_blank_key}": ["rate==1"],
    "checks{scenario:concurrency}": ["rate==1"],
    // Enforce the exact concurrency invariant: capacity=10 → 10 allowed, 90 denied
    concurrency_allowed: ["count==10"],
    concurrency_denied: ["count==90"],
  },
};

/** Scenario 1: Fresh UUID key — must be allowed (200 + allowed=true) */
export function singleAllowed() {
  const key = uuidv4();
  const res = http.post(ENDPOINT, JSON.stringify({ key }), { headers: HEADERS });
  check(res, {
    "single allowed → 200": (r) => r.status === 200,
    "single allowed → allowed=true": (r) => JSON.parse(r.body).allowed === true,
  });
}

/**
 * Scenario 2: Exhaust a bucket.
 * First CAPACITY requests on the same key must be allowed; the (CAPACITY+1)th must be denied.
 */
export function bucketExhaustion() {
  const key = `exhaust-${uuidv4()}`;

  for (let i = 0; i < CAPACITY; i++) {
    const res = http.post(ENDPOINT, JSON.stringify({ key }), { headers: HEADERS });
    check(res, {
      [`request ${i + 1} allowed → 200`]: (r) => r.status === 200,
    });
  }

  // CAPACITY+1 must be denied
  const denied = http.post(ENDPOINT, JSON.stringify({ key }), { headers: HEADERS });
  check(denied, {
    "exhausted bucket → 429": (r) => r.status === 429,
    "exhausted bucket → allowed=false": (r) => JSON.parse(r.body).allowed === false,
    "exhausted bucket → Retry-After present": (r) => r.headers["Retry-After"] !== undefined,
  });

  sleep(1);
}

/** Scenario 3: Missing key field — must return 400 */
export function validationMissingKey() {
  const res = http.post(ENDPOINT, JSON.stringify({}), { headers: HEADERS });
  check(res, {
    "missing key → 400": (r) => r.status === 400,
  });
}

/** Scenario 4: Blank key value — must return 400 */
export function validationBlankKey() {
  const res = http.post(ENDPOINT, JSON.stringify({ key: "" }), { headers: HEADERS });
  check(res, {
    "blank key → 400": (r) => r.status === 400,
  });
}

/**
 * Scenario 5: Concurrency burst.
 * 100 VUs all hit the same key simultaneously. Bucket capacity = 10,
 * so exactly 10 must return 200 and 90 must return 429.
 *
 * Counter metrics aggregate across all VU runtimes; plain JS variables do not.
 */
const CONCURRENCY_KEY = `burst-${uuidv4()}`;
const allowedCounter = new Counter("concurrency_allowed");
const deniedCounter = new Counter("concurrency_denied");

export function concurrencyBurst() {
  const res = http.post(ENDPOINT, JSON.stringify({ key: CONCURRENCY_KEY }), { headers: HEADERS });

  if (res.status === 200) {
    allowedCounter.add(1);
    check(res, {
      "burst allowed → allowed=true": (r) => JSON.parse(r.body).allowed === true,
    });
  } else {
    deniedCounter.add(1);
    check(res, {
      "burst denied → 429": (r) => r.status === 429,
      "burst denied → allowed=false": (r) => JSON.parse(r.body).allowed === false,
    });
  }
}

export function handleSummary(data) {
  const allowedCount = data.metrics.concurrency_allowed?.values?.count ?? 0;
  const deniedCount = data.metrics.concurrency_denied?.values?.count ?? 0;
  console.log(`\nConcurrency burst: ${allowedCount} allowed / ${deniedCount} denied (capacity=${CAPACITY})`);
  return {};
}
