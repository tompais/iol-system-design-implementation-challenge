package com.iol.ratelimiter.adapter.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handler for rate-limiter adapter exceptions.
 *
 * Maps [RateLimitExceededException] to a 429 response with `Retry-After` header and
 * `{"allowed":false}` body. Keeping this mapping here (rather than in the handler) is
 * what makes [RateLimitHandler] truly thin — the handler delegates, never decides the
 * HTTP response for the denied case.
 */
@RestControllerAdvice
class RateLimitExceptionHandler {
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceeded(ex: RateLimitExceededException): ResponseEntity<RateLimitResponse> =
        ResponseEntity
            .status(ex.statusCode)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(RateLimitResponse(false))
}
