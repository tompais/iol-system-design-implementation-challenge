package com.iol.ratelimiter.infra

import com.iol.ratelimiter.core.domain.BucketState
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult
import com.iol.ratelimiter.core.domain.TokenBucketConfig
import com.iol.ratelimiter.core.port.BucketStore
import com.iol.ratelimiter.core.port.Clock
import com.iol.ratelimiter.core.port.RateLimiterPort

private const val ONE_MILLI_TOKEN = 1_000L
private const val MS_PER_SECOND = 1_000L

/**
 * Token Bucket rate limiter with lazy refill and CAS-based thread safety.
 *
 * **Algorithm**
 * Each bucket holds `milliTokens` (Long). One token = 1000 milliTokens, chosen so that
 * sub-token refills can accumulate without floating-point arithmetic. Refill is computed
 * on every [tryConsume] call from elapsed wall time — no background threads required.
 *
 * **Thread safety**
 * The CAS loop in [tryConsume] retries if another thread concurrently modifies the bucket.
 * This eliminates the TOCTOU race where two threads both read a non-zero balance,
 * both decide "allowed", and both subtract — spending the same token twice.
 *
 * See [TokenBucketConcurrencyTest] for the race-condition regression test.
 */
class TokenBucketRateLimiter(
    private val config: TokenBucketConfig,
    private val store: BucketStore,
    private val clock: Clock,
) : RateLimiterPort {
    override fun tryConsume(key: RateLimitKey): RateLimitResult {
        val ref = store.getOrCreate(key) { initialState() }
        while (true) {
            val current = ref.get()
            val refilled = computeRefill(current)
            if (refilled.milliTokens < ONE_MILLI_TOKEN) return RateLimitResult.Denied(retryAfterSeconds(refilled))
            val next = refilled.copy(milliTokens = refilled.milliTokens - ONE_MILLI_TOKEN)
            // CAS: if state changed between read and write, retry with fresh read
            if (ref.compareAndSet(current, next)) return RateLimitResult.Allowed
        }
    }

    private fun initialState() =
        BucketState(
            milliTokens = config.capacity * ONE_MILLI_TOKEN,
            lastRefillAt = clock.nowMillis(),
        )

    private fun computeRefill(state: BucketState): BucketState {
        val elapsedMs = clock.nowMillis() - state.lastRefillAt
        // refillRatePerSecond [tokens/sec] × elapsedMs [ms] = earned milliTokens
        // (units: tokens/sec × ms = tokens·ms/sec = milliTokens, since 1000ms/sec × 1000mt/token cancel)
        val earned = config.refillRatePerSecond * elapsedMs
        val capped = minOf(state.milliTokens + earned, config.capacity * ONE_MILLI_TOKEN)
        return state.copy(milliTokens = capped, lastRefillAt = clock.nowMillis())
    }

    private fun retryAfterSeconds(state: BucketState): Long {
        val missingMilliTokens = ONE_MILLI_TOKEN - state.milliTokens
        // Rate in milliTokens/ms = refillRatePerSecond (1 token/sec = 1 mt/ms).
        // ms needed = ceil(missingMilliTokens / refillRatePerSecond).
        // Seconds needed = ceil(msNeeded / 1000) — always ≥ 1 for integer rates ≥ 1.
        val msNeeded = (missingMilliTokens + config.refillRatePerSecond - 1) / config.refillRatePerSecond
        return (msNeeded + MS_PER_SECOND - 1L) / MS_PER_SECOND
    }
}
