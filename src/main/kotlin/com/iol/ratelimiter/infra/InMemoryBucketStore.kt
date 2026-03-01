package com.iol.ratelimiter.infra

import com.iol.ratelimiter.core.domain.BucketState
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.port.BucketStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [BucketStore] backed by a [ConcurrentHashMap].
 *
 * [computeIfAbsent] is atomic — only one thread initializes the entry for a new key.
 * All subsequent callers receive the same [AtomicReference] instance, which is the
 * shared state required by the CAS loop in [TokenBucketRateLimiter].
 */
class InMemoryBucketStore : BucketStore {
    private val store = ConcurrentHashMap<RateLimitKey, AtomicReference<BucketState>>()

    override fun getOrCreate(
        key: RateLimitKey,
        initial: () -> BucketState,
    ): AtomicReference<BucketState> = store.computeIfAbsent(key) { AtomicReference(initial()) }
}
