package com.iol.ratelimiter.adapter.api

import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult
import com.iol.ratelimiter.core.port.RateLimiterPort
import com.iol.sdimplementationchallenge.SdImplementationChallengeApplication
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Integration tests for the full HTTP layer: [RateLimitHandler] + [RateLimitExceptionHandler].
 *
 * Uses `@SpringBootTest(RANDOM_PORT)` so the exception handler advice (`@RestControllerAdvice`)
 * and bean validation are both active. [RateLimiterPort] is mocked via `@MockkBean` to keep
 * tests isolated from the algorithm implementation.
 *
 * Verifies:
 * - 200 OK with `{"allowed":true}` on success
 * - 429 with `Retry-After` header and `{"allowed":false}` on denial (via exception handler)
 * - 400 on missing or blank key (bean validation)
 */
@SpringBootTest(
    classes = [SdImplementationChallengeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@DisplayName("RateLimitHandler — HTTP contract")
class RateLimitHandlerTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: WebTestClient

    @MockkBean
    private lateinit var rateLimiter: RateLimiterPort

    @BeforeEach
    fun setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    @DisplayName("allowed result → 200 OK with allowed=true body")
    fun `allowed result returns 200 with allowed body`() {
        every { rateLimiter.tryConsume(RateLimitKey("user-1")) } returns RateLimitResult.Allowed

        client
            .post()
            .uri("/api/rate-limit/check")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"key":"user-1"}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.allowed")
            .isEqualTo(true)
    }

    @Test
    @DisplayName("denied result → 429 with allowed=false body and Retry-After header")
    fun `denied result returns 429 with Retry-After header and denied body`() {
        every { rateLimiter.tryConsume(RateLimitKey("user-1")) } returns RateLimitResult.Denied(5L)

        client
            .post()
            .uri("/api/rate-limit/check")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"key":"user-1"}""")
            .exchange()
            .expectStatus()
            .isEqualTo(429)
            .expectHeader()
            .valueEquals("Retry-After", "5")
            .expectBody()
            .jsonPath("$.allowed")
            .isEqualTo(false)
    }

    @Test
    @DisplayName("missing key field → 400 Bad Request")
    fun `missing key field returns 400`() {
        client
            .post()
            .uri("/api/rate-limit/check")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    @DisplayName("blank key value → 400 Bad Request")
    fun `blank key value returns 400`() {
        client
            .post()
            .uri("/api/rate-limit/check")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"key":""}""")
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
