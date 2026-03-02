package com.iol.ratelimiter.adapter.api.filters

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

/**
 * Captures HTTP metrics for WebFlux Functional Router endpoints.
 *
 * Spring Boot Actuator doesn't automatically instrument coRouter (functional API),
 * so we manually record metrics here:
 * - http_server_requests_seconds_count (counter)
 * - http_server_requests_seconds_sum (duration sum)
 * - http_server_requests_seconds_max (max duration)
 *
 * Metrics include labels:
 * - method: HTTP method (GET, POST, etc)
 * - uri: request path
 * - status: HTTP response status code
 */
class MetricsFilter(
    private val meterRegistry: MeterRegistry,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val startNs = System.nanoTime()
        val request = exchange.request
        val method = request.method.toString()
        val path = request.uri.path

        return chain
            .filter(exchange)
            .doFinally {
                val statusCode =
                    exchange.response.statusCode
                        ?.value()
                        ?.toString() ?: "UNKNOWN"
                val elapsedNs = System.nanoTime() - startNs
                val elapsedSeconds = elapsedNs / 1_000_000_000.0

                // Record metrics using MeterRegistry
                Timer
                    .builder("http_server_requests_seconds")
                    .tag("method", method)
                    .tag("uri", path)
                    .tag("status", statusCode)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(elapsedNs, TimeUnit.NANOSECONDS)

                log.debug("Recorded metrics - method=$method uri=$path status=$statusCode duration=${elapsedSeconds}s")
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MetricsFilter::class.java)
    }
}
