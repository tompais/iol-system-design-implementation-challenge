package com.iol.ratelimiter.adapter.api.errors.handler

import com.iol.ratelimiter.adapter.api.errors.exceptions.BadRequestException
import com.iol.ratelimiter.adapter.api.responses.RateLimitResponse
import com.iol.ratelimiter.core.domain.RateLimitDeniedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RateLimitExceptionHandler {
    @ExceptionHandler(RateLimitDeniedException::class)
    fun handleRateLimitDenied(ex: RateLimitDeniedException): ResponseEntity<RateLimitResponse> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(RateLimitResponse(false))

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<List<String>> = ResponseEntity.badRequest().body(ex.violations)
}
