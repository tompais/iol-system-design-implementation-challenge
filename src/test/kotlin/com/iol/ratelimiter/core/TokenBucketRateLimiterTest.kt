package com.iol.ratelimiter.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import com.iol.ratelimiter.core.domain.RateLimitDeniedException
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.TokenBucketConfig
import com.iol.ratelimiter.core.port.Clock
import com.iol.ratelimiter.infra.InMemoryBucketStore
import com.iol.ratelimiter.infra.TokenBucketRateLimiter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Unit tests for the Token Bucket algorithm implemented in [TokenBucketRateLimiter].
 *
 * Uses a controllable [Clock] stub so all timing is deterministic — no real-time dependencies.
 * Capacity = 10 tokens, refill rate = 5 tokens/sec (1 token per 200 ms) unless overridden per test.
 *
 * Concurrency correctness is covered separately in [TokenBucketConcurrencyTest].
 */
@DisplayName("TokenBucketRateLimiter — algorithm correctness")
class TokenBucketRateLimiterTest {
    private var now = 0L
    private val clock = Clock { now }
    private val config = TokenBucketConfig(capacity = 10L, refillRatePerSecond = 5L)

    private lateinit var limiter: TokenBucketRateLimiter

    @BeforeEach
    fun setUp() {
        now = 0L
        limiter = TokenBucketRateLimiter(config, InMemoryBucketStore(), clock)
    }

    @Test
    @DisplayName("first request on a fresh bucket is allowed")
    fun `first request allowed`() {
        assertDoesNotThrow { limiter.tryConsume(RateLimitKey("u")) }
    }

    @Test
    @DisplayName("initial bucket is full — exactly capacity requests are allowed, the next is denied")
    fun `exactly capacity requests allowed then denied`() {
        val key = RateLimitKey("u")
        repeat(10) { assertDoesNotThrow { limiter.tryConsume(key) } }
        assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) }
    }

    @Test
    @DisplayName("Denied exception carries a retryAfterSeconds value of at least 1")
    fun `denied exception has positive retryAfterSeconds`() {
        val key = RateLimitKey("u")
        repeat(10) { limiter.tryConsume(key) }
        val ex = assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) }
        assertThat(ex.retryAfterSeconds).isGreaterThanOrEqualTo(1L)
    }

    @Test
    @DisplayName("after 200 ms (1 token refilled at 5/sec), a denied bucket becomes allowed again")
    fun `refill after 200ms restores one token`() {
        val key = RateLimitKey("u")
        repeat(10) { limiter.tryConsume(key) }
        now += 200L
        assertDoesNotThrow { limiter.tryConsume(key) }
    }

    /**
     * Parameterized partial-refill scenarios.
     *
     * At 5 tokens/sec, each millisecond earns 5 milliTokens. One full token = 1000 milliTokens.
     * After 100 ms → 500 milliTokens earned (< 1000) → still denied.
     * After 200 ms → 1000 milliTokens earned → allowed.
     */
    @ParameterizedTest(name = "after {0} ms elapsed: result is {1}")
    @CsvSource(
        "100, denied",
        "199, denied",
        "200, allowed",
    )
    @DisplayName("sub-token partial refill: only a full 1000 milliTokens unlocks a request")
    fun `partial refill does not allow a request`(
        elapsedMs: Long,
        expected: String,
    ) {
        val key = RateLimitKey("u")
        repeat(10) { limiter.tryConsume(key) }
        now += elapsedMs
        if (expected == "allowed") {
            assertDoesNotThrow { limiter.tryConsume(key) }
        } else {
            assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) }
        }
    }

    @Test
    @DisplayName("refill is capped at capacity — no token overflow above bucket size")
    fun `refill does not exceed capacity`() {
        val key = RateLimitKey("u")
        // Advance 10 seconds → would refill 50 tokens, but capacity is 10
        now += 10_000L
        repeat(10) { assertDoesNotThrow { limiter.tryConsume(key) } }
        assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) }
    }

    @Test
    @DisplayName("frozen clock — no time passes, denied stays denied")
    fun `frozen clock keeps bucket exhausted`() {
        val key = RateLimitKey("u")
        repeat(10) { limiter.tryConsume(key) }
        repeat(5) { assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) } }
    }

    @Test
    @DisplayName("two keys share no state — each has its own independent bucket")
    fun `two keys are independent`() {
        val a = RateLimitKey("a")
        val b = RateLimitKey("b")
        repeat(10) { limiter.tryConsume(a) }
        assertDoesNotThrow { limiter.tryConsume(b) }
    }

    @Test
    @DisplayName("fresh bucket starts full — initial state equals capacity")
    fun `initial bucket is full`() {
        val key = RateLimitKey("fresh")
        // A full bucket allows exactly capacity requests with zero time elapsed
        repeat(10) { assertDoesNotThrow { limiter.tryConsume(key) } }
    }

    @Test
    @DisplayName("retryAfterSeconds is the ceiling of 1 token at the current refill rate")
    fun `retryAfterSeconds is ceiling of refill period`() {
        val key = RateLimitKey("u")
        repeat(10) { limiter.tryConsume(key) }
        // At 5 tokens/sec, 1 token takes 200ms = 0.2 sec → ceiling = 1
        val ex = assertThrows<RateLimitDeniedException> { limiter.tryConsume(key) }
        assertThat(ex.retryAfterSeconds).isEqualTo(1L)
    }

    @Test
    @DisplayName("large forward clock jump does not overflow — bucket refills to at most capacity")
    fun `large forward clock jump caps refill at capacity`() {
        var clockNow = 0L
        val cfg = TokenBucketConfig(capacity = 5L, refillRatePerSecond = 1L)
        val fwdLimiter = TokenBucketRateLimiter(cfg, InMemoryBucketStore(), Clock { clockNow })
        val key = RateLimitKey("fwd-jump-test")

        repeat(5) { assertDoesNotThrow { fwdLimiter.tryConsume(key) } }
        assertThrows<RateLimitDeniedException> { fwdLimiter.tryConsume(key) }

        // Jump forward by years — bucket must refill to exactly capacity, not overflow
        clockNow = Long.MAX_VALUE / 2
        repeat(5) { assertDoesNotThrow { fwdLimiter.tryConsume(key) } }
        assertThrows<RateLimitDeniedException> { fwdLimiter.tryConsume(key) }
    }

    @Test
    @DisplayName("clock regression does not reduce token balance")
    fun `clock regression does not reduce token balance`() {
        var clockNow = 1000L
        val cfg = TokenBucketConfig(capacity = 5L, refillRatePerSecond = 1L)
        val regLimiter = TokenBucketRateLimiter(cfg, InMemoryBucketStore(), Clock { clockNow })
        val key = RateLimitKey("regression-test")

        repeat(3) { assertDoesNotThrow { regLimiter.tryConsume(key) } }

        // Clock steps backward by 500ms (NTP correction)
        clockNow = 500L

        // 2 tokens remain; regression must not destroy them
        assertDoesNotThrow { regLimiter.tryConsume(key) }
        assertDoesNotThrow { regLimiter.tryConsume(key) }
        assertThrows<RateLimitDeniedException> { regLimiter.tryConsume(key) }
    }
}
