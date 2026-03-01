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
| `<Name>ExceededException.kt` | `adapter/api/errors/exceptions/` → `...adapter.api.errors.exceptions` |
| Exception handler entry | `adapter/api/errors/handler/RateLimitExceptionHandler.kt` |
| `<Name>Router.kt` + `<Name>RouterOperations.kt` | `adapter/api/routing/` → `...adapter.api.routing` |

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

import com.iol.ratelimiter.adapter.api.errors.exceptions.<Name>ExceededException
import com.iol.ratelimiter.adapter.api.requests.<Name>Request
import com.iol.ratelimiter.adapter.api.responses.<Name>Response
import com.iol.ratelimiter.core.domain.RateLimitKey
import com.iol.ratelimiter.core.domain.RateLimitResult
import com.iol.ratelimiter.core.port.RateLimiterPort
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Validator
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait

class <Name>Handler(
    private val rateLimiter: RateLimiterPort,
    private val validator: Validator,
) {
    suspend fun <verb>(request: ServerRequest): ServerResponse {
        val body = request.awaitBody<<Name>Request>()
        val errors = BeanPropertyBindingResult(body, "<name>Request")
        validator.validate(body, errors)
        if (errors.hasErrors()) return ServerResponse.badRequest().bodyValueAndAwait(errors.allErrors.map { it.defaultMessage })

        return when (val result = rateLimiter.tryConsume(RateLimitKey(body.key))) {
            is RateLimitResult.Allowed -> ServerResponse.ok().bodyValueAndAwait(<Name>Response(allowed = true))
            is RateLimitResult.Denied  -> throw <Name>ExceededException(result.retryAfterSeconds)
        }
    }
}
```

**Handler rules (non-negotiable):**
- Handler functions are `suspend fun` — always
- Use `awaitBody<T>()` to read the request body
- Use `buildAndAwait()` or `bodyValueAndAwait(...)` to build responses
- The `Denied` branch **throws** — never builds a 429 response directly in the handler
- HTTP 429 mapping and `Retry-After` header live in the `@ExceptionHandler`, not here
- Handler contains **NO business logic** — no if/else on domain fields, no computation
- Validation runs via `Validator`, error response is the only exception to the throw-on-deny rule

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

### `<Name>ExceededException.kt`

```kotlin
package com.iol.ratelimiter.adapter.api.errors.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class <Name>ExceededException(
    val retryAfterSeconds: Long,
) : ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

### `<Name>ExceptionHandler.kt` (or add to the existing `RateLimitExceptionHandler`)

```kotlin
@ExceptionHandler(<Name>ExceededException::class)
fun handle<Name>Exceeded(ex: <Name>ExceededException): ResponseEntity<<Name>Response> =
    ResponseEntity
        .status(ex.statusCode)
        .header("Retry-After", ex.retryAfterSeconds.toString())
        .body(<Name>Response(false))
```

**Why this pattern?**
- The handler stays truly thin — it never decides the HTTP shape of an error response
- `@RestControllerAdvice` centralises all error-to-HTTP mappings in one auditable file
- New exception types are added to the advice class, not scattered across handler files

---

## OpenAPI — `@<Name>RouterOperations` Composed Annotation

SpringDoc cannot introspect functional `coRouter` routes, so metadata must be declared explicitly.
Use the composed-annotation pattern — one annotation per router, placed on the `@Bean` method.

### `<Name>RouterOperations.kt`

```kotlin
package com.iol.ratelimiter.adapter.api.routing

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
fun <name>Handler(rateLimiter: RateLimiterPort, validator: Validator) = <Name>Handler(rateLimiter, validator)

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
- [ ] `<Name>Handler.kt` — `suspend fun`, validates via `Validator`, throws on `Denied`
- [ ] `<Name>ExceededException.kt` — extends `ResponseStatusException(TOO_MANY_REQUESTS)`
- [ ] `@ExceptionHandler` entry in `RateLimitExceptionHandler` (or new advice class)
- [ ] Router entry added to `RateLimiterRouter.kt`
- [ ] `<Name>RouterOperations.kt` composed annotation + placed on `@Bean` in config
- [ ] Bean registered in `RateLimiterConfig.kt`
- [ ] Handler test added in `adapter/api/<Name>HandlerTest.kt` (package `com.iol.ratelimiter.adapter.api`) using `WebTestClient` + `@MockkBean`
- [ ] `./gradlew build` passes
