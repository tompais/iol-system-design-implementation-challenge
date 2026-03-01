package com.iol.ratelimiter.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult
import com.iol.ratelimiter.core.domain.TokenBucketConfig
import com.iol.ratelimiter.core.port.Clock
import com.iol.ratelimiter.infra.InMemoryBucketStore
import com.iol.ratelimiter.infra.TokenBucketRateLimiter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Race-condition regression test for the CAS loop in [TokenBucketRateLimiter].
 *
 * **Why this test exists**
 * Without the CAS loop, two threads can both read `milliTokens = 1000`, both decide "allowed",
 * and both call `ref.set(state - 1_token)` — spending the same token twice. Naive `ref.set()`
 * would cause `allowed > capacity` non-deterministically.
 *
 * **Test design**
 * 100 threads are held at a [CountDownLatch] then released simultaneously to maximise contention.
 * A bucket with `capacity = 10` must allow exactly 10 and deny 90 — every run, every repetition.
 * `@RepeatedTest(10)` drives it 10 times to expose flakiness if the fix is incomplete.
 */
@DisplayName("TokenBucketRateLimiter — CAS thread safety")
class TokenBucketConcurrencyTest {
    @RepeatedTest(10)
    @DisplayName("exactly capacity requests are allowed across 100 concurrent threads")
    fun `exactly capacity requests allowed under 100 concurrent threads`() {
        val config = TokenBucketConfig(capacity = 10L, refillRatePerSecond = 1L)
        val limiter = TokenBucketRateLimiter(config, InMemoryBucketStore(), Clock { System.currentTimeMillis() })
        val key = RateLimitKey("race")
        val latch = CountDownLatch(1)
        val allowed = AtomicInteger()
        val denied = AtomicInteger()

        val threads =
            (1..100).map {
                Thread {
                    latch.await()
                    when (limiter.tryConsume(key)) {
                        is RateLimitResult.Allowed -> allowed.incrementAndGet()
                        is RateLimitResult.Denied -> denied.incrementAndGet()
                    }
                }
            }
        threads.forEach(Thread::start)
        latch.countDown()
        threads.forEach { it.join(5_000) }

        assertThat(allowed.get()).isEqualTo(10)
        assertThat(denied.get()).isEqualTo(90)
    }
}
