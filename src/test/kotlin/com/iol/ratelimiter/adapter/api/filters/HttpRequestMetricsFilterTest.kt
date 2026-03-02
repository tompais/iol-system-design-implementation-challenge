package com.iol.ratelimiter.adapter.api.filters

import assertk.assertThat
import assertk.assertions.isNotNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class HttpRequestMetricsFilterTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val filter = HttpRequestMetricsFilter(meterRegistry)

    @Test
    fun `records http server requests timer with correct tags on 200 OK`() {
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/rate-limit/check").build(),
            )
        val chain =
            WebFilterChain { ex ->
                ex.response.statusCode = HttpStatus.OK
                Mono.empty()
            }

        filter.filter(exchange, chain).block()

        assertThat(
            meterRegistry
                .find("http.server.requests")
                .tag("method", "POST")
                .tag("uri", "/api/rate-limit/check")
                .tag("status", "200")
                .tag("outcome", "SUCCESSFUL")
                .timer(),
        ).isNotNull()
    }

    @Test
    fun `records CLIENT_ERROR outcome on 429 Too Many Requests`() {
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/rate-limit/check").build(),
            )
        val chain =
            WebFilterChain { ex ->
                ex.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                Mono.empty()
            }

        filter.filter(exchange, chain).block()

        assertThat(
            meterRegistry
                .find("http.server.requests")
                .tag("status", "429")
                .tag("outcome", "CLIENT_ERROR")
                .timer(),
        ).isNotNull()
    }

    @Test
    fun `records REDIRECTION outcome on 301 Moved Permanently`() {
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/old-path").build(),
            )
        val chain =
            WebFilterChain { ex ->
                ex.response.statusCode = HttpStatus.MOVED_PERMANENTLY
                Mono.empty()
            }

        filter.filter(exchange, chain).block()

        assertThat(
            meterRegistry
                .find("http.server.requests")
                .tag("status", "301")
                .tag("outcome", "REDIRECTION")
                .timer(),
        ).isNotNull()
    }

    @Test
    fun `records SERVER_ERROR outcome on 500 Internal Server Error`() {
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/rate-limit/check").build(),
            )
        val chain =
            WebFilterChain { ex ->
                ex.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
                Mono.empty()
            }

        filter.filter(exchange, chain).block()

        assertThat(
            meterRegistry
                .find("http.server.requests")
                .tag("status", "500")
                .tag("outcome", "SERVER_ERROR")
                .timer(),
        ).isNotNull()
    }

    @Test
    fun `records UNKNOWN outcome when response has no status code`() {
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/rate-limit/check").build(),
            )
        val chain = WebFilterChain { Mono.empty() }

        filter.filter(exchange, chain).block()

        assertThat(
            meterRegistry
                .find("http.server.requests")
                .tag("status", "UNKNOWN")
                .tag("outcome", "UNKNOWN")
                .timer(),
        ).isNotNull()
    }
}
