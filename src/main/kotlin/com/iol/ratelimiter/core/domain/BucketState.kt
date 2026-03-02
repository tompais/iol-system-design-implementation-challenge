package com.iol.ratelimiter.core.domain

/**
 * Immutable snapshot of a single bucket's state, used as the CAS target in
 * [com.iol.ratelimiter.infra.TokenBucketRateLimiter].
 *
 * Stored as `milliTokens` (Long, not Double) so refill and retry-after calculations
 * use exact integer arithmetic with no floating-point rounding drift:
 * 1 token = 1_000 milliTokens. Note: `AtomicReference.compareAndSet` operates on
 * reference identity, not field values — the choice of Long is about arithmetic
 * correctness, not CAS semantics.
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
