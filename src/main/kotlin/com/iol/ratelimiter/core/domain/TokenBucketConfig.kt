package com.iol.ratelimiter.core.domain

data class TokenBucketConfig(
    val capacity: Long,
    val refillRatePerSecond: Long,
)
