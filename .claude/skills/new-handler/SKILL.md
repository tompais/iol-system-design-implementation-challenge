# /new-handler — WebFlux Functional Endpoint Scaffold

Scaffold a complete WebFlux Functional endpoint following the project's thin-handler convention. Every new endpoint requires exactly 4 pieces.

---

## Package Location

All files go in: `src/main/kotlin/com/iol/ratelimiter/adapter/api/`

---

## The 4-Piece Pattern

### 1. `<Name>Request.kt` — Request DTO

```kotlin
package com.iol.ratelimiter.adapter.api

import jakarta.validation.constraints.NotBlank

data class <Name>Request(
    @field:NotBlank
    val key: String,
    // add domain-specific fields here
)
```

**Validation rules:**
- Validation annotations go on the DTO, NOT in the handler
- Use `@field:NotBlank`, `@field:Min`, `@field:Max` etc. as needed
- Mark the parameter `@Valid` in the handler's `awaitBody<T>()` call if using Spring validation

---

### 2. `<Name>Response.kt` — Response DTO

```kotlin
package com.iol.ratelimiter.adapter.api

data class <Name>Response(
    val allowed: Boolean,
    // add response fields here
)
```

Keep response DTOs flat — no nested objects unless the API contract requires it.

---

### 3. `<Name>Handler.kt` — Thin Handler

```kotlin
package com.iol.ratelimiter.adapter.api

import com.iol.ratelimiter.core.port.RateLimiterPort
import com.iol.ratelimiter.core.domain.RateLimitKey
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait

class <Name>Handler(private val rateLimiter: RateLimiterPort) {

    suspend fun <verb>(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<<Name>Request>()
        return when (val result = rateLimiter.tryConsume(RateLimitKey(body.key))) {
            is RateLimitResult.Allowed -> ServerResponse.ok()
                .bodyValueAndAwait(<Name>Response(allowed = true))
            is RateLimitResult.Denied  -> ServerResponse.status(429)
                .header("Retry-After", result.retryAfterSeconds.toString())
                .bodyValueAndAwait(<Name>Response(allowed = false))
        }
    }
}
```

**Handler rules (non-negotiable):**
- Handler functions are `suspend fun` — always
- Use `awaitBody<T>()` to read the request body
- Use `buildAndAwait()` or `bodyValueAndAwait(...)` to build responses
- Handler maps HTTP status (`200`, `429`, etc.) from the domain result — nothing else
- Handler contains **NO business logic** — no if/else on domain fields, no computation
- Handler does NOT validate — validation is on the DTO

---

### 4. Router entry in `RateLimiterRouter.kt`

```kotlin
fun rateLimitRouter(
    rateLimitHandler: RateLimitHandler,
    <name>Handler: <Name>Handler,
) = coRouter {
    POST("/api/rate-limit/check", rateLimitHandler::check)
    // Add your new route:
    <METHOD>("<path>", <name>Handler::<verb>)
}
```

**coRouter rules:**
- Routing only — no logic in the router
- One line per route
- Route paths follow `/api/<resource>/<action>` convention

---

## Wiring in `RateLimiterConfig.kt`

Register the new handler as a Spring bean:

```kotlin
@Bean
fun <name>Handler(rateLimiter: RateLimiterPort) = <Name>Handler(rateLimiter)

@Bean
fun rateLimitRouter(
    rateLimitHandler: RateLimitHandler,
    <name>Handler: <Name>Handler,
) = rateLimitRouter(rateLimitHandler, <name>Handler)
```

---

## Checklist

- [ ] `<Name>Request.kt` — validation on DTO fields
- [ ] `<Name>Response.kt` — flat response DTO
- [ ] `<Name>Handler.kt` — `suspend fun`, no business logic, maps HTTP status
- [ ] Router entry added to `RateLimiterRouter.kt`
- [ ] Bean registered in `RateLimiterConfig.kt`
- [ ] Handler test added in `adapter/api/<Name>HandlerTest.kt` using `WebTestClient` + `@MockkBean`
- [ ] `./gradlew build` passes
