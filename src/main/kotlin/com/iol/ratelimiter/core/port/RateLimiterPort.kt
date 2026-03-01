package com.iol.ratelimiter.core.port

import com.iol.ratelimiter.core.domain.RateLimitKey

fun interface RateLimiterPort {
    // Returns on success; throws RateLimitDeniedException if the bucket is empty.
    fun tryConsume(key: RateLimitKey)
}
