# Docker Networking & Prometheus Setup - Complete Guide

## 🔴 El Problema Raíz (Root Cause)

### Problema 1: localhost en Docker

El error en tu setup anterior era **`localhost` en Docker**:

```yaml
# ❌ INCORRECTO (lo que tenías)
zipkin:
  tracing:
    endpoint: http://localhost:9411/api/v2/spans
```

Dentro de un contenedor Docker:
- `localhost` = **el contenedor mismo** (127.0.0.1 del contenedor)
- `localhost:9411` intenta alcanzar el puerto 9411 **dentro del contenedor `app`**
- Pero Zipkin corre en un **contenedor diferente** (`grafana-lgtm`)

### Problema 2: OTLP Registry Auto-Configuration

Otro error común es tener **`micrometer-registry-otlp` en las dependencias**:

```
Failed to publish metrics to OTLP receiver (url=http://localhost:4318/v1/metrics)
java.net.ConnectException: Connection refused
```

**¿Por qué pasa?**
- Spring Boot auto-configura **TODOS** los registries que encuentra en el classpath
- Si tienes `micrometer-registry-otlp` + `micrometer-registry-prometheus`, intenta usar ambos
- OTLP intenta enviar métricas a `localhost:4318` (que no existe)
- Prometheus funciona correctamente (scraping `/actuator/prometheus`)

**Solución:** Remover `micrometer-registry-otlp` del `build.gradle.kts`:

```kotlin
// ❌ INCORRECTO - causa connection refused
runtimeOnly("io.micrometer:micrometer-registry-otlp")
runtimeOnly("io.micrometer:micrometer-registry-prometheus")

// ✅ CORRECTO - solo Prometheus
runtimeOnly("io.micrometer:micrometer-registry-prometheus")
```

// ...existing code...

