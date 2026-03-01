package com.iol.sdimplementationchallenge

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

/**
 * Smoke integration tests that verify the complete application wiring and HTTP contract
 * against a real server on a random port.
 *
 * These tests complement the unit/slice tests: they prove the full stack —
 * Spring context, configuration, router, handler, validation, exception handler,
 * and the Token Bucket algorithm — all work together end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Application smoke tests")
class SdImplementationChallengeApplicationTests {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: WebTestClient

    @BeforeEach
    fun setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    @Suppress("EmptyFunctionBlock")
    @DisplayName("Spring context loads without errors")
    fun contextLoads() {
        // Spring Boot context must start without exceptions — no explicit assertion needed.
    }

    @Test
    @DisplayName("fresh key returns 200 with allowed=true")
    fun `fresh key returns 200 allowed`() {
        client
            .post()
            .uri("/api/rate-limit/check")
            .header("Content-Type", "application/json")
            .bodyValue("""{"key":"smoke-${UUID.randomUUID()}"}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.allowed")
            .value(equalTo(true))
    }
}
