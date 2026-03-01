package com.iol.ratelimiter.adapter.api.handlers

import com.iol.ratelimiter.adapter.api.requests.RateLimitRequest
import com.iol.ratelimiter.adapter.api.responses.RateLimitResponse
import com.iol.ratelimiter.adapter.api.validation.BodyValidator
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.port.RateLimiterPort
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait

class RateLimitHandler(
    private val rateLimiter: RateLimiterPort,
    private val bodyValidator: BodyValidator,
) {
    suspend fun check(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<RateLimitRequest>()
        bodyValidator.validate(body)
        rateLimiter.tryConsume(RateLimitKey(body.key))
        return ServerResponse.ok().bodyValueAndAwait(RateLimitResponse(true))
    }
}
