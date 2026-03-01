package com.iol.ratelimiter.adapter.api.errors.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

// Thrown by BodyValidator when Jakarta constraints are violated.
// Carries all violation messages so the ExceptionHandler can return them in the response body.
class BadRequestException(
    val violations: List<String>,
) : ResponseStatusException(HttpStatus.BAD_REQUEST) {
    companion object {
        private const val serialVersionUID = 1L
    }
}
