# Observability & Metrics

## Overview

El Rate Limiter implementa observabilidad completa a través de:

1. **Structured Logging** con Log4j2 + ThreadContext (MDC)
2. **Distributed Tracing** con OpenTelemetry (OTLP)
3. **Metrics** con Micrometer → Prometheus
4. **Visualización** con Grafana + LGTM Stack

Esta arquitectura sigue la recomendación de Spring Boot para observabilidad moderna.

---

## Componentes de Observabilidad

### 1. Logging (Log4j2)

**Configuración**: `src/main/resources/log4j2-spring.yaml`

Patrón de salida:
```
[TIMESTAMP] [THREAD] [LEVEL] [LOGGER] [TRACE_ID=%X{traceId}] [SPAN_ID=%X{spanId}] MESSAGE
```

**Niveles configurados:**

```yaml
logging:
  level:
    root: WARN
    com.iol.ratelimiter: TRACE
    com.iol.sdimplementationchallenge: TRACE
```

**Ventajas:**

- Todos los logs del rate limiter incluyen automáticamente `traceId` y `spanId`.
- Correlación automática entre logs, traces y métricas en Grafana.
- No requiere cambio manual de código para llenar el MDC (Spring Boot lo hace).

### 2. Distributed Tracing (OpenTelemetry)

**Stack de ejecución:**

```
Spring Boot Actuator Tracing
       ↓
   Brave (implementation)
       ↓
   OpenTelemetry API (OTEL SDK)
       ↓
   OTLP Exporter (gRPC)
       ↓
   Grafana LGTM (tempo + loki)
       ↓
   Grafana UI
```

**Configuración**: Auto-cableada en `application.yaml`

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # Capturar 100% de los traces (ajustar en producción a 0.1-0.01)
```

**Ventajas:**

- Traces distribuidos automáticos en cada request HTTP.
- Visible en Grafana → **Explore** → **Traces**.
- Permite correlacionar múltiples servicios (si se escala).

### 3. Metrics (Micrometer)

**Métricas automáticas:**

| Métrica | Fuente | Descripción |
|---|---|---|
| `http_server_requests_seconds` | Spring WebFlux | Histograma de latencia HTTP |
| `jvm_memory_*` | JVM | Memoria heap/non-heap |
| `jvm_threads_*` | JVM | Estado de threads |
| `process_cpu_usage` | OS | CPU del proceso |
| `jvm_gc_*` | JVM | Eventos y duraciones de GC |
| `system_*` | OS | CPU, memoria, cargas del sistema |

**Registros de métricas:**

1. **Prometheus Registry** (`/actuator/prometheus`):
   - Formato Prometheus (text/plain).
   - Puerto 8080 (mismo que la app).
   - Consumida por Prometheus cada 10 segundos (ver `prometheus.yaml`).

2. **OTLP Registry** (puerto 4317 gRPC):
   - Exporta a Grafana LGTM.
   - Redundante con Prometheus, pero útil para escalar a múltiples Grafanas.

---

## Stack Local: Grafana LGTM

### Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                     docker compose                       │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ┌──────────────────┐         ┌────────────────────┐    │
│  │      app:8080    │         │ grafana-lgtm:3000  │    │
│  │  Spring Boot     │         │   (Grafana)        │    │
│  │  Rate Limiter    │ OTLP→ ┃ │                    │    │
│  │                  │ gRPC │ └────────────────────┘    │
│  │ /actuator/prom.  │      ├─ Prometheus (9090)        │
│  │ (Prometheus)     │      ├─ Tempo (4317 gRPC)        │
│  │                  │      ├─ Loki (3100)              │
│  │                  │      └─ OpenTelemetry Collector  │
│  └──────────────────┘                                  │
│         ↑ (scrape)                                      │
│         │                                               │
│    Prometheus                                           │
│    (10s interval)                                       │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

### Componentes

**`grafana/otel-lgtm`** incluye:

- **Grafana**: UI principal.
- **Prometheus**: Time-series database para métricas.
- **Tempo**: Backend para traces distribuidos.
- **Loki**: Agregador de logs.
- **OpenTelemetry Collector**: Recibe OTLP (gRPC) y distribuye.

### Variables de Entorno

En `compose.yaml`:

```yaml
environment:
  OTEL_EXPORTER_OTLP_ENDPOINT: http://grafana-lgtm:4317
  OTEL_EXPORTER_OTLP_PROTOCOL: grpc
  OTEL_SERVICE_NAME: rate-limiter
  MANAGEMENT_OTLP_METRICS_EXPORT_URL: http://grafana-lgtm:4318/v1/metrics
```

**Notas:**

- Puerto **4317** (gRPC): para traces y logs.
- Puerto **4318** (HTTP): para métricas. Micrometer usa HTTP/protobuf, no gRPC.
- `OTEL_SERVICE_NAME`: etiqueta automáticamente traces/logs/métricas con nombre del servicio.

---

## Iniciando la Stack Observabilidad

### Opción 1: `bootRun` (Recomendado)

```bash
./gradlew bootRun
```

Spring Boot DevTools automáticamente:
1. Compila y carga la aplicación.
2. Ejecuta `docker compose up -d` para el stack LGTM.
3. Espera a que esté listo.
4. Expone la aplicación en `http://localhost:8080`.

**Verificar que está activa:**

```bash
curl http://localhost:8080/actuator/health
```

Debería responder con JSON de health.

### Opción 2: Compose directo

```bash
docker compose up -d
```

Luego ejecutar la aplicación en tu IDE o terminal.

### Opción 3: Compose + Build de contenedor

```bash
docker compose build
docker compose up -d
```

Construye la imagen de la app (usando `Dockerfile`) y arranca todo.

---

## Accediendo a Observabilidad

### Logs (Loki en Grafana)

1. Abre http://localhost:3000
2. Ve a **Explore** (ícono de compass)
3. Selecciona **Loki** en el dropdown de datasources
4. Escribe una query LogQL:

```logql
{job="rate-limiter"} | json
```

Filtra por `traceId`:

```logql
{job="rate-limiter"} | json | traceId="abc123"
```

### Traces (Tempo en Grafana)

1. Ve a **Explore** → **Tempo**
2. Selecciona **Search**
3. Filtra por service name: `rate-limiter`
4. Haz clic en un trace para ver el span tree

**Correlación automática:**

- Haz clic en un log en Loki → auto-navega al trace correspondiente.
- Haz clic en un trace → auto-muestra los logs asociados.

### Métricas (Prometheus en Grafana)

1. Ve a **Explore** → **Prometheus**
2. Escribe una PromQL query:

```promql
rate(http_server_requests_seconds_count[1m])
```

Visualiza por URI:

```promql
sum by (uri, method, status) (rate(http_server_requests_seconds_count[1m]))
```

### Dashboard Preconfigurado

1. Ve a **Dashboards** → **Rate Limiter - Metrics & System Performance**
2. Visualiza paneles de latencia, throughput, JVM, y rate limit rejections.

Si no ves el dashboard, importa manualmente:
- Ve a **Dashboards** → **+ Import**
- Carga `grafana/dashboards/rate-limiter-metrics.json`
- Selecciona datasource **Prometheus**
- Haz clic en **Import**

---

## Configuración de Alertas

### Ejemplo: Alertar si Rate Limit > 30%

En Grafana UI:

1. Ve al panel "Rate Limit Rejection Rate" en el dashboard.
2. Haz clic en **Alert** (campana).
3. Configura:

```
Condition: when last() of A is above 30
For: 5 minutes
```

4. Configura notificación (Slack, email, etc.) en **Alerting** → **Contact points**.

---

## Métricas Recomendadas para Monitoreo

### KPIs del Rate Limiter

| Métrica | PromQL | Umbral | Acción |
|---|---|---|---|
| **Tasa de solicitudes** | `sum(rate(http_server_requests_seconds_count[1m]))` | N/A | Monitor |
| **Tasa de rechazos** | `sum(rate(http_server_requests_seconds_count{status="429"}[1m]))` | >10% del tráfico | Aumentar refill rate |
| **Latencia p99** | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[1m]))` | <100ms | Monitor |
| **Errores 5xx** | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))` | >1 error/min | Investigar |

### KPIs de la JVM

| Métrica | PromQL | Umbral | Acción |
|---|---|---|---|
| **Memoria heap** | `(jvm_memory_usage_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100` | >85% | Aumentar `-Xmx` |
| **GC pause** | `jvm_gc_pause_seconds_count` | >100ms | Ajustar GC tuning |
| **Threads live** | `jvm_threads_live_threads` | >50 | Buscar resource leak |
| **CPU proceso** | `process_cpu_usage * 100` | >80% | Escalar horizontalmente |

---

## Troubleshooting

### Las métricas no aparecen

**Causa**: Prometheus no está scrapeando.

**Solución**:

1. Verifica que el endpoint de Prometheus está activo:
   ```bash
   curl http://localhost:8080/actuator/prometheus | head -20
   ```

2. Verifica que Prometheus está scrapeando:
   - http://localhost:9090/targets
   - El job `rate-limiter` debe estar `UP`

3. Si está `DOWN`, verifica los logs:
   ```bash
   docker compose logs grafana-lgtm | grep prometheus
   ```

### Los logs no aparecen en Loki

**Causa**: Loki está recibiendo logs pero con formato incorrecto.

**Solución**:

1. Verifica que Log4j2 está escribiendo a stdout:
   ```bash
   docker compose logs app | grep "rate-limiter"
   ```

2. Si los logs no aparecen, verifica la configuración de Log4j2 en `log4j2-spring.yaml`.

### Traces no correlacionan con logs

**Causa**: El MDC no está siendo propagado automáticamente.

**Solución**:

- Spring Boot 4.0+ lo hace automáticamente.
- Verifica que `spring-boot-micrometer-tracing-brave` está en el classpath:
  ```bash
  ./gradlew dependencies | grep tracing
  ```

---

## Performance Tuning

### Reducir Overhead de Tracing en Producción

Por defecto, capturamos 100% de los traces. En producción, ajusta:

```yaml
management:
  tracing:
    sampling:
      probability: 0.1  # Capturar 10% de los traces
```

Esto reduce overhead a CPU/memoria.

### Reducir Retention en Prometheus

En `prometheus.yaml`:

```yaml
global:
  scrape_interval: 30s  # Aumentar a 30 segundos desde 10 segundos
  evaluation_interval: 30s
```

Reduce la carga de Prometheus.

### Memory Leaks en JVM

Monitor `jvm_threads_live_threads`:

```promql
jvm_threads_live_threads
```

Si sube constantemente, investiga con `jvm_memory_usage_bytes`.

---

## Referencias

- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Metrics](https://micrometer.io/docs/concepts)
- [OpenTelemetry](https://opentelemetry.io/docs/)
- [Prometheus PromQL](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana Dashboards](https://grafana.com/docs/grafana/latest/dashboards/)
- [Grafana Alerting](https://grafana.com/docs/grafana/latest/alerting/)

