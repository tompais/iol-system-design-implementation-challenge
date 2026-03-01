package com.iol.ratelimiter

import com.iol.ratelimiter.adapter.api.RateLimitHandler
import com.iol.ratelimiter.adapter.api.RateLimiterRouterOperations
import com.iol.ratelimiter.core.domain.TokenBucketConfig
import com.iol.ratelimiter.core.port.RateLimiterPort
import com.iol.ratelimiter.infra.InMemoryBucketStore
import com.iol.ratelimiter.infra.SystemClock
import com.iol.ratelimiter.infra.TokenBucketRateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.Validator
import com.iol.ratelimiter.adapter.api.rateLimitRouter as buildRateLimitRouter

/**
 * Wires all rate-limiter beans. Single source of truth for the dependency graph —
 * no `@Component` or `@Autowired` annotations exist in `infra/` or `adapter/`.
 *
 * `@RateLimiterRouterOperations` provides SpringDoc with the OpenAPI metadata it cannot infer
 * from functional routes (coRouter has no annotations for introspection).
 * All route documentation is centralised in that composed annotation.
 */
@Configuration
class RateLimiterConfig {
    @Bean
    fun tokenBucketConfig(
        @Value("\${rate-limiter.capacity}") capacity: Long,
        @Value("\${rate-limiter.refill-rate-per-second}") refillRatePerSecond: Long,
    ) = TokenBucketConfig(capacity, refillRatePerSecond)

    @Bean
    fun bucketStore() = InMemoryBucketStore()

    @Bean
    fun rateLimiter(config: TokenBucketConfig): RateLimiterPort = TokenBucketRateLimiter(config, bucketStore(), SystemClock)

    @Bean
    fun rateLimitHandler(
        rateLimiter: RateLimiterPort,
        validator: Validator,
    ) = RateLimitHandler(rateLimiter, validator)

    @Bean
    @RateLimiterRouterOperations
    fun rateLimitRouter(handler: RateLimitHandler) = buildRateLimitRouter(handler)
}
