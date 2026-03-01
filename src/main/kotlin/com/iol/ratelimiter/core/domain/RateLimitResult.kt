package com.iol.ratelimiter.core.domain

sealed class RateLimitResult {
    data object Allowed : RateLimitResult()

    data class Denied(
        val retryAfterSeconds: Long,
    ) : RateLimitResult()
}
