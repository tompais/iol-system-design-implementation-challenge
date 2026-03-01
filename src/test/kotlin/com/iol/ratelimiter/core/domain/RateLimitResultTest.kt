package com.iol.ratelimiter.core.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

class RateLimitResultTest {
    @Test
    fun `Allowed is a singleton`() {
        assertThat(RateLimitResult.Allowed).isSameInstanceAs(RateLimitResult.Allowed)
    }

    @Test
    fun `Denied exposes retryAfterSeconds`() {
        assertThat(RateLimitResult.Denied(30L).retryAfterSeconds).isEqualTo(30L)
    }

    @Test
    fun `when on sealed type is exhaustive without else`() {
        // This test is a compile-time proof: if a branch were missing, this would not compile.
        val result: RateLimitResult = RateLimitResult.Allowed
        val label =
            when (result) {
                is RateLimitResult.Allowed -> "allowed"
                is RateLimitResult.Denied -> "denied"
            }
        assertThat(label).isEqualTo("allowed")
    }
}
