package com.iol.ratelimiter.adapter.api.filters

import org.slf4j.LoggerFactory
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class RequestLoggingFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val startNs = System.nanoTime()
        val request = exchange.request
        log.info("> {} {}", request.method, request.uri.path)
        return chain.filter(exchange).doFinally {
            val status =
                exchange.response.statusCode
                    ?.value()
                    ?.toString() ?: "unknown"
            log.info("< {} {} {} ({}ms)", request.method, request.uri.path, status, (System.nanoTime() - startNs) / 1_000_000)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
    }
}
