package com.iol.ratelimiter.core.domain

/**
 * Type-safe wrapper for a rate-limit bucket identifier (e.g., a user ID, API key, or IP address).
 *
 * Declared as a `@JvmInline value class` so the JVM erases it to a plain `String` at runtime —
 * zero heap allocation overhead compared to a regular wrapper class. The type exists only at
 * compile time to prevent accidentally passing an arbitrary string where a rate-limit key is
 * expected (and vice versa). Equality is structural: `RateLimitKey("u1") == RateLimitKey("u1")`.
 */
@JvmInline
value class RateLimitKey(
    val value: String,
)
