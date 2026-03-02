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
    fun `records http server requests timer on successful request`() {
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

        assertThat(meterRegistry.find("http.server.requests").timer()).isNotNull()
    }

    @Test
    fun `records http server requests timer on denied request`() {
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
}
