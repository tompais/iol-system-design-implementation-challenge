package com.iol.ratelimiter.core.domain

/**
 * Immutable snapshot of a single bucket's state, used as the CAS target in
 * [com.iol.ratelimiter.infra.TokenBucketRateLimiter].
 *
 * Stored as `milliTokens` (Long, not Double) to enable exact integer CAS:
 * 1 token = 1_000 milliTokens. IEEE 754 Doubles can produce non-identical bit
 * patterns for logically equal values, making `AtomicReference.compareAndSet`
 * unreliable. Integer arithmetic is always exact.
 *
 * Example: at `refillRatePerSecond=5`, 100ms earns 500 milliTokens (half a token),
 * which accumulates without loss until 1_000 milliTokens (one full token) is
 * available to spend.
 *
 * `data class` provides structural `equals`/`hashCode` and the `copy()` used for
 * immutable state transitions inside the CAS loop.
 */
data class BucketState(
    val milliTokens: Long,
    val lastRefillAt: Long,
)
