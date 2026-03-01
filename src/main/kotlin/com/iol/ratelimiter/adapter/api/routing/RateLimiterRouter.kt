package com.iol.ratelimiter.adapter.api.routing

import com.iol.ratelimiter.adapter.api.handlers.RateLimitHandler
import org.springframework.web.reactive.function.server.coRouter

fun rateLimitRouter(handler: RateLimitHandler) =
    coRouter {
        POST("/api/rate-limit/check", handler::check)
    }
