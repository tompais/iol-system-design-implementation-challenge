package com.iol.ratelimiter.core.domain

/**
 * Immutable configuration snapshot for the Token Bucket algorithm.
 *
 * Both values are sourced from `application.yaml` (`rate-limiter.capacity` and
 * `rate-limiter.refill-rate-per-second`) and injected by [com.iol.ratelimiter.RateLimiterConfig].
 *
 * - [capacity]: maximum tokens the bucket can hold — controls burst tolerance.
 *   Example: `capacity=10` allows up to 10 simultaneous requests before denial.
 * - [refillRatePerSecond]: tokens earned per second — controls sustained throughput.
 *   Example: `refillRatePerSecond=5` allows 5 requests/sec after the burst is spent,
 *   and means a full bucket (capacity=10) is restored after 2 seconds of inactivity.
 *
 * Both values are `Long` so they multiply cleanly with `elapsedMs` (also `Long`)
 * in the milliToken arithmetic without risk of floating-point imprecision.
 */
data class TokenBucketConfig(
    val capacity: Long,
    val refillRatePerSecond: Long,
)
