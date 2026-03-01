package com.iol.ratelimiter.adapter.api

import jakarta.validation.constraints.NotBlank

data class RateLimitRequest(
    @field:NotBlank val key: String,
)
