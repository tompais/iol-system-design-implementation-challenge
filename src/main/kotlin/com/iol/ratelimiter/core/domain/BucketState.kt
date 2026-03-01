package com.iol.ratelimiter.core.domain

// milliTokens (Long, not Double) enables exact integer CAS — 1 token = 1_000 milliTokens.
// IEEE 754 Doubles can produce non-identical bit patterns for logically equal values;
// integer CAS is always exact.
data class BucketState(
    val milliTokens: Long,
    val lastRefillAt: Long,
)
