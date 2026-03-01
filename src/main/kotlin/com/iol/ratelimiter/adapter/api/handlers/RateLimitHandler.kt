package com.iol.ratelimiter.adapter.api.handlers

import com.iol.ratelimiter.adapter.api.errors.exceptions.RateLimitExceededException
import com.iol.ratelimiter.adapter.api.requests.RateLimitRequest
import com.iol.ratelimiter.adapter.api.responses.RateLimitResponse
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult
import com.iol.ratelimiter.core.port.RateLimiterPort
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Validator
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/**
 * Thin WebFlux handler for the rate-limit check endpoint.
 *
 * Validates the request body, delegates to [RateLimiterPort], and returns 200 on success.
 * On denial, throws [RateLimitExceededException] — the HTTP 429 mapping and `Retry-After`
 * header are handled by the exception handler, keeping this class free of conditional
 * response-building logic.
 */
class RateLimitHandler(
    private val rateLimiter: RateLimiterPort,
    private val validator: Validator,
) {
    suspend fun check(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<RateLimitRequest>()
        val errors = BeanPropertyBindingResult(body, "rateLimitRequest")
        validator.validate(body, errors)
        if (errors.hasErrors()) return ServerResponse.badRequest().bodyValueAndAwait(errors.allErrors.map { it.defaultMessage })

        return when (val result = rateLimiter.tryConsume(RateLimitKey(body.key))) {
            is RateLimitResult.Allowed -> ServerResponse.ok().bodyValueAndAwait(RateLimitResponse(true))
            is RateLimitResult.Denied -> throw RateLimitExceededException(result.retryAfterSeconds)
        }
    }
}
