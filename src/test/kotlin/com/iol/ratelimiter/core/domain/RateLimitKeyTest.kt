package com.iol.ratelimiter.core.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test

class RateLimitKeyTest {
    @Test
    fun `same value produces equal keys`() {
        assertThat(RateLimitKey("user-1")).isEqualTo(RateLimitKey("user-1"))
    }

    @Test
    fun `different values produce non-equal keys`() {
        assertThat(RateLimitKey("user-1")).isNotEqualTo(RateLimitKey("user-2"))
    }

    @Test
    fun `value property exposes the underlying string`() {
        assertThat(RateLimitKey("user-1").value).isEqualTo("user-1")
    }

    @Test
    fun `equal keys hash the same — usable as map key`() {
        val map = hashMapOf(RateLimitKey("user-1") to 42)
        assertThat(map[RateLimitKey("user-1")]).isEqualTo(42)
    }
}
