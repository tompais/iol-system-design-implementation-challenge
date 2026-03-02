package com.iol.ratelimiter.adapter.api.filters

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Records `http.server.requests` timer metrics for every inbound request.
 *
 * WebFlux Functional (coRouter) routes do not carry URI-template annotations, so Spring Boot's
 * `ServerHttpObservationFilter` cannot derive a low-cardinality `uri` tag and the metric is
 * never emitted. This filter fills that gap by recording the timer directly against the
 * `MeterRegistry`, using the raw request path as the `uri` tag (acceptable here because the
 * application exposes a single, fixed route with no path variables).
 */
class HttpRequestMetricsFilter(
    private val meterRegistry: MeterRegistry,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val path = exchange.request.uri.path

        // Excluir rutas de OpenAPI, Swagger y Actuator de métricas
        if (ExcludedPaths.isExcluded(path)) {
            return chain.filter(exchange)
        }

        val sample = Timer.start(meterRegistry)
        return chain.filter(exchange).doFinally {
            val statusCode = exchange.response.statusCode?.value()
            sample.stop(
                Timer
                    .builder(HTTP_SERVER_REQUESTS)
                    .tag("method", exchange.request.methodValue ?: "UNKNOWN")
                    .tag("uri", path)
                    .tag("status", statusCode?.toString() ?: "UNKNOWN")
                    .tag("outcome", outcome(statusCode))
                    .register(meterRegistry),
            )
        }
    }

    private fun outcome(statusCode: Int?): String = statusCode?.let { HttpStatus.Series.resolve(it)?.name } ?: "UNKNOWN"

    companion object {
        private const val HTTP_SERVER_REQUESTS = "http.server.requests"
    }
}
