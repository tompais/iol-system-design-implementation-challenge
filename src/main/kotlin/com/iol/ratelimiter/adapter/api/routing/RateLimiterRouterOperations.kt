package com.iol.ratelimiter.adapter.api.routing

import com.iol.ratelimiter.adapter.api.handlers.RateLimitHandler
import com.iol.ratelimiter.adapter.api.requests.RateLimitRequest
import com.iol.ratelimiter.adapter.api.responses.RateLimitResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springdoc.core.annotations.RouterOperation
import org.springdoc.core.annotations.RouterOperations
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestMethod

/**
 * Composed annotation that bundles all SpringDoc OpenAPI metadata for the rate-limiter router.
 *
 * Functional WebFlux routes (coRouter) carry no annotations that SpringDoc can introspect,
 * so each route's contract must be declared explicitly. Collecting all declarations here
 * means documentation changes touch exactly one file instead of scattering `@RouterOperation`
 * blocks across `@Configuration` classes.
 *
 * Usage: place this annotation on the `@Bean` method that returns the `RouterFunction`.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@RouterOperations(
    RouterOperation(
        path = "/api/rate-limit/check",
        method = [RequestMethod.POST],
        beanClass = RateLimitHandler::class,
        beanMethod = "check",
        operation =
            Operation(
                operationId = "checkRateLimit",
                summary = "Check and consume a rate-limit token for the given key",
                description =
                    "Attempts to consume one token from the bucket identified by `key`. " +
                        "Returns 200 if the request is allowed, 429 if the bucket is empty. " +
                        "The `Retry-After` response header on a 429 indicates the minimum wait " +
                        "in seconds before the next token is available.",
                tags = ["Rate Limiter"],
                requestBody =
                    RequestBody(
                        required = true,
                        content = [Content(schema = Schema(implementation = RateLimitRequest::class))],
                    ),
                responses = [
                    ApiResponse(
                        responseCode = "200",
                        description = "Request allowed — token consumed successfully",
                        content = [Content(schema = Schema(implementation = RateLimitResponse::class))],
                    ),
                    ApiResponse(
                        responseCode = "429",
                        description = "Rate limit exceeded — `Retry-After` header indicates wait time in seconds",
                        content = [Content(schema = Schema(implementation = RateLimitResponse::class))],
                    ),
                    ApiResponse(responseCode = "400", description = "Missing or blank `key` field"),
                ],
            ),
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    ),
)
annotation class RateLimiterRouterOperations
