package com.iol.ratelimiter.core.domain

class RateLimitDeniedException(
    val retryAfterSeconds: Long,
) : RuntimeException("Rate limit exceeded. Retry after ${retryAfterSeconds}s.")
