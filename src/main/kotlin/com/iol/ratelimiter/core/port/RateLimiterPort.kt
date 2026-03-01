package com.iol.ratelimiter.core.port

import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult

interface RateLimiterPort {
    // Synchronous (not suspend) — the algorithm is pure CPU arithmetic, no I/O.
    fun tryConsume(key: RateLimitKey): RateLimitResult
}
