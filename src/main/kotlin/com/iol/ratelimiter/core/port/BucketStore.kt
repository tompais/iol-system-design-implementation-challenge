package com.iol.ratelimiter.core.port

import com.iol.ratelimiter.core.domain.BucketState
import com.iol.ratelimiter.core.domain.RateLimitKey
import java.util.concurrent.atomic.AtomicReference

fun interface BucketStore {
    // Returns the same AtomicReference across concurrent calls for the same key —
    // the CAS loop in TokenBucketRateLimiter requires a shared reference, not a copy.
    fun getOrCreate(
        key: RateLimitKey,
        initial: () -> BucketState,
    ): AtomicReference<BucketState>
}
