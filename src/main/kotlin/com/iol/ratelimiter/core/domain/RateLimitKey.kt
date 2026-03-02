package com.iol.ratelimiter.core.domain

/**
 * Type-safe wrapper for a rate-limit bucket identifier (e.g., a user ID, API key, or IP address).
 *
 * Declared as a `@JvmInline value class` so that in many call sites the JVM can
 * represent it as the underlying `String` without an extra wrapper object, while
 * still providing a distinct type at compile time. It may still be boxed in some
 * situations (for example, when used with generics or as a `ConcurrentHashMap` key).
 * The type exists only at compile time to prevent accidentally passing an arbitrary
 * string where a rate-limit key is expected (and vice versa).
 * Equality is structural: `RateLimitKey("u1") == RateLimitKey("u1")`.
 */
@JvmInline
value class RateLimitKey(
    val value: String,
)
