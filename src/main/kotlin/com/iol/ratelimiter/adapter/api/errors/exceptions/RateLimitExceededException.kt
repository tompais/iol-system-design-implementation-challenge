package com.iol.ratelimiter.adapter.api.errors.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Thrown by [com.iol.ratelimiter.adapter.api.handlers.RateLimitHandler] when the rate limiter denies a request.
 *
 * Wraps [ResponseStatusException] (429 Too Many Requests) so Spring's exception handling
 * infrastructure maps it to the correct HTTP response without any conditional logic in the handler.
 * The [retryAfterSeconds] value is surfaced via the standard `Retry-After` header.
 */
class RateLimitExceededException(
    val retryAfterSeconds: Long,
) : ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) {
    companion object {
        private const val serialVersionUID = 1L
    }
}
