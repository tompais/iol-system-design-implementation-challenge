# /new-handler — WebFlux Functional Endpoint Scaffold

Scaffold a complete WebFlux Functional endpoint following the project's thin-handler convention. Every new endpoint requires exactly 4 pieces, plus an exception class and a composed OpenAPI annotation.

---

## Package Location

Files go into sub-packages of `src/main/kotlin/com/iol/ratelimiter/adapter/api/`:

| File | Sub-package |
|------|-------------|
| `<Name>Request.kt` | `adapter/api/requests/` → `...adapter.api.requests` |
| `<Name>Response.kt` | `adapter/api/responses/` → `...adapter.api.responses` |
| `<Name>Handler.kt` | `adapter/api/handlers/` → `...adapter.api.handlers` |
| `<Name>DeniedException.kt` | `core/domain/` → `...core.domain` |
| Exception handler entry | `adapter/api/errors/handler/RateLimitExceptionHandler.kt` |
| `<Name>Router.kt` | `adapter/api/routing/routers/` → `...adapter.api.routing.routers` |
| `<Name>RouterOperations.kt` | `adapter/api/routing/routers/operations/annotations/` → `...routing.routers.operations.annotations` |

---

## The 4-Piece Pattern

### 1. `<Name>Request.kt` — Request DTO

```kotlin
package com.iol.ratelimiter.adapter.api.requests

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

---

### 2. `<Name>Response.kt` — Response DTO

```kotlin
package com.iol.ratelimiter.adapter.api.responses

data class <Name>Response(
    val allowed: Boolean,
    // add response fields here
)
```

Keep response DTOs flat — no nested objects unless the API contract requires it.

---

### 3. `<Name>Handler.kt` — Thin Handler

```kotlin
package com.iol.ratelimiter.adapter.api.handlers

import com.iol.ratelimiter.adapter.api.requests.<Name>Request
import com.iol.ratelimiter.adapter.api.responses.<Name>Response
import com.iol.ratelimiter.adapter.api.validation.BodyValidator
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.port.RateLimiterPort
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait

class <Name>Handler(
    private val rateLimiter: RateLimiterPort,
    private val bodyValidator: BodyValidator,
) {
    suspend fun <verb>(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<<Name>Request>()
        bodyValidator.validate(body)                       // throws BadRequestException → 400
        rateLimiter.tryConsume(RateLimitKey(body.key))     // throws RateLimitDeniedException → @RestControllerAdvice
        return ServerResponse.ok().bodyValueAndAwait(<Name>Response(allowed = true))
    }
}
```

**Handler rules (non-negotiable):**
- Handler functions are `suspend fun` — always
- Use `awaitBody<T>()` to read the request body
- Use `buildAndAwait()` or `bodyValueAndAwait(...)` to build responses
- Domain service **throws** on denial — handler never builds 429 responses directly
- HTTP status mapping and `Retry-After` header live in `RateLimitExceptionHandler`, not here
- Handler contains **NO business logic** — no if/else on domain fields, no computation
- Validation runs via `bodyValidator.validate(body)`; constraint annotations go on the DTO

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

## Exception + ExceptionHandler Pattern

### `<Name>DeniedException.kt` — lives in `core/domain/` (pure Kotlin, no Spring)

```kotlin
package com.iol.ratelimiter.core.domain

class <Name>DeniedException(
    val retryAfterSeconds: Long,
) : RuntimeException("<name> denied. Retry after ${retryAfterSeconds}s.")
```

Domain exceptions are pure Kotlin — no Spring imports. This keeps `core/domain/` framework-free and testable without the application context.

### Exception handler entry (add to the existing `RateLimitExceptionHandler`)

```kotlin
@ExceptionHandler(RateLimitDeniedException::class)
fun handle<Name>Denied(ex: RateLimitDeniedException): ResponseEntity<<Name>Response> =
    ResponseEntity
        .status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", ex.retryAfterSeconds.toString())
        .body(<Name>Response(false))
```

> **Building a genuinely new domain operation?** Define `<Name>DeniedException` in `core/domain/` (pure Kotlin `RuntimeException`, no Spring), throw it from your domain service, and add a matching `@ExceptionHandler` entry above — following the same pattern as `RateLimitDeniedException`.

**Why this pattern?**
- The handler stays truly thin — it never decides the HTTP shape of an error response
- Domain exception lives in `core/domain/` — survives framework migrations unchanged
- `@RestControllerAdvice` centralises all error-to-HTTP mappings in one auditable file
- New exception types are added to the advice class, not scattered across handler files

---

## OpenAPI — `@<Name>RouterOperations` Composed Annotation

SpringDoc cannot introspect functional `coRouter` routes, so metadata must be declared explicitly.
Use the composed-annotation pattern — one annotation per router, placed on the `@Bean` method.

### `<Name>RouterOperations.kt`

```kotlin
package com.iol.ratelimiter.adapter.api.routing.routers.operations.annotations

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springdoc.core.annotations.RouterOperation
import org.springdoc.core.annotations.RouterOperations
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestMethod

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@RouterOperations(
    RouterOperation(
        path = "<path>",
        method = [RequestMethod.<METHOD>],
        beanClass = <Name>Handler::class,
        beanMethod = "<verb>",
        operation = Operation(
            operationId = "<operationId>",
            summary = "<summary>",
            tags = ["<tag>"],
            requestBody = RequestBody(
                required = true,
                content = [Content(schema = Schema(implementation = <Name>Request::class))],
            ),
            responses = [
                ApiResponse(responseCode = "200", description = "...",
                    content = [Content(schema = Schema(implementation = <Name>Response::class))]),
                ApiResponse(responseCode = "429", description = "Rate limit exceeded — Retry-After header in seconds",
                    content = [Content(schema = Schema(implementation = <Name>Response::class))]),
                ApiResponse(responseCode = "400", description = "Missing or blank key field"),
            ],
        ),
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    ),
)
annotation class <Name>RouterOperations
```

Place the annotation on the `@Bean` method in `RateLimiterConfig.kt`:
```kotlin
@Bean
@<Name>RouterOperations
fun rateLimitRouter(handler: RateLimitHandler, ...) = rateLimitRouter(handler, ...)
```

See `RateLimiterRouterOperations.kt` for the complete reference implementation.

---

## Wiring in `RateLimiterConfig.kt`

```kotlin
@Bean
fun <name>Handler(rateLimiter: RateLimiterPort, bodyValidator: BodyValidator) =
    <Name>Handler(rateLimiter, bodyValidator)

@Bean
@<Name>RouterOperations
fun rateLimitRouter(
    rateLimitHandler: RateLimitHandler,
    <name>Handler: <Name>Handler,
) = rateLimitRouter(rateLimitHandler, <name>Handler)
```

---

## Checklist

- [ ] `<Name>Request.kt` — validation on DTO fields
- [ ] `<Name>Response.kt` — flat response DTO
- [ ] `<Name>Handler.kt` — `suspend fun`, validates via `bodyValidator.validate(body)`, linear (no when/if on result)
- [ ] `<Name>DeniedException.kt` in `core/domain/` — pure Kotlin `RuntimeException`, no Spring imports
- [ ] `@ExceptionHandler` entry in `RateLimitExceptionHandler` (or new advice class)
- [ ] Router entry added to `RateLimiterRouter.kt`
- [ ] `<Name>RouterOperations.kt` composed annotation + placed on `@Bean` in config
- [ ] Bean registered in `RateLimiterConfig.kt`
- [ ] Handler test added in `adapter/api/<Name>HandlerTest.kt` (package `com.iol.ratelimiter.adapter.api`) using `WebTestClient` + `@MockkBean`
- [ ] `./gradlew build` passes
