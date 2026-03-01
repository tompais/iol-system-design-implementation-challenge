package com.iol.ratelimiter.infra

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.iol.ratelimiter.core.domain.BucketState
import com.iol.ratelimiter.core.domain.RateLimitKey
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InMemoryBucketStore].
 *
 * Verifies key isolation and the "create-once" contract: the same [AtomicReference] must be
 * returned on every call for a given key so that concurrent CAS loops share state.
 */
@DisplayName("InMemoryBucketStore")
class InMemoryBucketStoreTest {
    private lateinit var store: InMemoryBucketStore

    @BeforeEach
    fun setUp() {
        store = InMemoryBucketStore()
    }

    @Test
    @DisplayName("first access calls the initializer")
    fun `new key calls initial lambda`() {
        var called = false
        store.getOrCreate(RateLimitKey("k")) {
            called = true
            BucketState(1_000L, 0L)
        }
        assertThat(called).isTrue()
    }

    @Test
    @DisplayName("second access returns the same AtomicReference without calling initializer again")
    fun `existing key skips initial lambda and returns same reference`() {
        val initial = BucketState(1_000L, 0L)
        val first = store.getOrCreate(RateLimitKey("k")) { initial }
        var secondCalled = false
        val second =
            store.getOrCreate(RateLimitKey("k")) {
                secondCalled = true
                initial
            }
        assertThat(secondCalled).isFalse()
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    @DisplayName("distinct keys produce independent AtomicReferences")
    fun `two different keys yield distinct references`() {
        val a = store.getOrCreate(RateLimitKey("a")) { BucketState(1_000L, 0L) }
        val b = store.getOrCreate(RateLimitKey("b")) { BucketState(1_000L, 0L) }
        assertThat(a).isNotSameInstanceAs(b)
    }
}
