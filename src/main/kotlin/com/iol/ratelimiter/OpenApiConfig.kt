package com.iol.ratelimiter

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configures the OpenAPI specification metadata shown in the Swagger UI at `/swagger-ui.html`.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Rate Limiter API")
                .version("1.0.0")
                .description(
                    """
                    Token Bucket rate limiter prototype — System Design Implementation Challenge.

                    Each call to `POST /api/rate-limit/check` attempts to consume one token from
                    the bucket identified by `key`. The bucket refills continuously at the configured
                    rate. When the bucket is empty the request is denied with HTTP 429 and a
                    `Retry-After` header indicating the minimum wait in seconds.

                    **Default config:** capacity=10 tokens, refill=5 tokens/sec.
                    """.trimIndent(),
                ),
        )
}
