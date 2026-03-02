package com.iol.ratelimiter.adapter.api.filters

/**
 * Paths that should be excluded from logging and metrics collection.
 *
 * These paths (OpenAPI, Swagger, Actuator) are infrastructure endpoints that
 * generate noise in logs and metrics without providing meaningful insights into
 * rate limiter behavior.
 */
object ExcludedPaths {
    val paths: Set<String> =
        setOf(
            "/swagger",
            "/v3/api-docs",
            "/swagger-ui",
            "/webjars",
            "/actuator",
        )

    fun isExcluded(path: String): Boolean = paths.any { path.startsWith(it) }
}
