# Métricas Disponibles — Rate Limiter

Referencia exhaustiva de todas las métricas recolectadas por la aplicación y cómo usarlas en Grafana.

---

## Fuentes de Métricas

### 1. Métricas HTTP (Micrometer WebFlux)

**Endpoint de recopilación:** `/actuator/prometheus` (Spring Boot Actuator)

**Métrica principal:**
```
http_server_requests_milliseconds{method="POST",status="200|429|400",uri="/api/rate-limit/check",outcome="SUCCESS|CLIENT_ERROR|SERVER_ERROR"}
```

**Variantes:**

| Métrica | Tipo | Descripción | Unidad |
|---|---|---|---|
| `http_server_requests_milliseconds_bucket` | Histogram | Buckets de latencia para cuantiles | milisegundos |
| `http_server_requests_milliseconds_count` | Counter | Número total de requests | count |
| `http_server_requests_milliseconds_sum` | Counter | Suma total de latencias | milisegundos |
| `http_server_requests_milliseconds_max` | Gauge | Latencia máxima | milisegundos |

**Labels disponibles:**
- `method`: HTTP method (POST)
- `uri`: Request path (`/api/rate-limit/check`)
- `status`: HTTP status code (200, 429, 400)
- `outcome`: SUCCESS, CLIENT_ERROR, SERVER_ERROR, REDIRECTION

**PromQL útil:**

```promql
# Latencia promedio (p50)
rate(http_server_requests_milliseconds_sum[1m]) / rate(http_server_requests_milliseconds_count[1m])

# Latencia p95
histogram_quantile(0.95, rate(http_server_requests_milliseconds_bucket[1m]))

# Latencia p99
histogram_quantile(0.99, rate(http_server_requests_milliseconds_bucket[1m]))

# Tasa de solicitudes por segundo (RPS)
sum(rate(http_server_requests_milliseconds_count[1m]))

# Requests permitidos (200)
rate(http_server_requests_milliseconds_count{status="200"}[1m])

# Requests rechazados (429)
rate(http_server_requests_milliseconds_count{status="429"}[1m])

# Requests con error de validación (400)
rate(http_server_requests_milliseconds_count{status="400"}[1m])

# Tasa de rechazo (%)
(sum(rate(http_server_requests_milliseconds_count{status="429"}[1m])) / sum(rate(http_server_requests_milliseconds_count[1m]))) * 100

# Total de requests por estado
sum by (status) (http_server_requests_milliseconds_count)
```

---

### 2. Métricas de JVM (Micrometer JVM Metrics)

Auto-recolectadas. No requieren configuración adicional.

#### Memory (Memoria)

```
jvm_memory_usage_bytes{area="heap|non_heap",id="Ps Eden Space|Ps Survivor Space|Ps Old Gen|..."}
jvm_memory_max_bytes{area="heap|non_heap",id="..."}
jvm_memory_committed_bytes{area="heap|non_heap",id="..."}
jvm_memory_init_bytes{area="heap|non_heap",id="..."}
```

**PromQL útil:**

```promql
# Memoria heap usada
jvm_memory_usage_bytes{area="heap"}

# Memoria máxima
jvm_memory_max_bytes{area="heap"}

# Porcentaje de heap usado
(jvm_memory_usage_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100

# Memoria non-heap usada
jvm_memory_usage_bytes{area="non_heap"}

# Tendencia de memoria (crecimiento en 5 minutos)
increase(jvm_memory_usage_bytes{area="heap"}[5m])

# Cambio por minuto
rate(jvm_memory_usage_bytes{area="heap"}[1m])
```

#### Threads (Threads)

```
jvm_threads_live_threads
jvm_threads_peak_threads
jvm_threads_daemon_threads
jvm_threads_started_threads_total
```

**PromQL útil:**

```promql
# Threads activos ahora
jvm_threads_live_threads

# Threads pico (máximo histórico)
jvm_threads_peak_threads

# Threads daemon
jvm_threads_daemon_threads

# Total threads creados
jvm_threads_started_threads_total

# Rate de creación de threads (por minuto)
rate(jvm_threads_started_threads_total[1m])
```

#### Garbage Collection (GC)

```
jvm_gc_pause_seconds{action="end of major GC|end of minor GC|..."}
jvm_gc_pause_seconds_count
jvm_gc_pause_seconds_sum
jvm_gc_pause_seconds_max
jvm_gc_memory_promoted_bytes_total
jvm_gc_max_data_size_bytes
```

**PromQL útil:**

```promql
# Latencia de GC (p99)
histogram_quantile(0.99, rate(jvm_gc_pause_seconds_bucket[1m]))

# Número de GC por minuto
rate(jvm_gc_pause_seconds_count[1m])

# Duración total de GC
sum(jvm_gc_pause_seconds_sum)

# Bytes promovidos (heap usado)
jvm_gc_memory_promoted_bytes_total

# Tamaño máximo de GC
jvm_gc_max_data_size_bytes
```

#### Classes (Clases)

```
jvm_classes_loaded_classes
jvm_classes_unloaded_classes_total
```

**PromQL útil:**

```promql
# Clases cargadas
jvm_classes_loaded_classes

# Tasa de carga
rate(jvm_classes_loaded_classes[1m])
```

---

### 3. Métricas del Proceso (Process)

```
process_cpu_usage                    # 0.0 - 1.0
process_cpu_time_seconds
process_uptime_seconds
process_files_open_descriptors
process_files_max_descriptors
```

**PromQL útil:**

```promql
# CPU usage (%)
process_cpu_usage * 100

# Uptime (segundos)
process_uptime_seconds

# Uptime (minutos)
process_uptime_seconds / 60

# Uptime (horas)
process_uptime_seconds / 3600

# Descriptores abiertos
process_files_open_descriptors

# % de descriptores usados
(process_files_open_descriptors / process_files_max_descriptors) * 100
```

---

### 4. Métricas del Sistema (System)

```
system_cpu_usage                     # 0.0 - 1.0
system_cpu_count
system_load_average_1m
system_load_average_5m
system_load_average_15m
```

**PromQL útil:**

```promql
# CPU del sistema (%)
system_cpu_usage * 100

# Número de CPUs
system_cpu_count

# Load average (1m)
system_load_average_1m

# Load average (5m)
system_load_average_5m

# Load average (15m)
system_load_average_15m

# Saturación: load_avg / cpu_count
system_load_average_1m / system_cpu_count
```

---

### 5. Métricas de Logback (Logging)

Auto-recolectadas.

```
logback_events_total{level="ERROR|WARN|INFO|DEBUG|TRACE"}
```

**PromQL útil:**

```promql
# Errores por minuto
rate(logback_events_total{level="ERROR"}[1m])

# Warnings por minuto
rate(logback_events_total{level="WARN"}[1m])

# Total de eventos de error
logback_events_total{level="ERROR"}
```

---

## Dashboard Recomendado

El dashboard `grafana/dashboards/rate-limiter-metrics.json` incluye todos estos paneles:

### Timeseries (Time-series charts)

1. **HTTP Request Latency (avg)**
   - Query: `rate(http_server_requests_milliseconds_sum[1m]) / rate(http_server_requests_milliseconds_count[1m])`
   - Muestra: Latencia promedio por URI/método/status
   - Unidad: segundos

2. **HTTP Request Rate**
   - Query: `rate(http_server_requests_milliseconds_count[1m])`
   - Muestra: RPS por URI/método/status
   - Unidad: requests por segundo

3. **Rate Limit Check Requests (Total)**
   - Query: `http_server_requests_milliseconds_count{uri="/api/rate-limit/check"}`
   - Muestra: Total acumulado desglosado por status
   - Unidad: count

4. **Rate Limit Rejection Rate**
   - Query: `(sum(rate(...{status="429"}[1m])) / sum(rate(...[1m]))) * 100`
   - Muestra: % de solicitudes rechazadas
   - Unidad: percent

5. **JVM Memory Usage**
   - Query: `jvm_memory_usage_bytes`
   - Muestra: Heap + non-heap
   - Unidad: bytes

6. **JVM Live Threads**
   - Query: `jvm_threads_live_threads`
   - Muestra: Threads activos
   - Unidad: count

7. **Process CPU Usage**
   - Query: `process_cpu_usage * 100`
   - Muestra: CPU del proceso
   - Unidad: percent

8. **JVM GC Max Data Size**
   - Query: `jvm_gc_max_data_size_bytes`
   - Muestra: Tamaño máximo de datos
   - Unidad: bytes

### Gauges (Current values)

9. **Avg Request Rate (5m)**
   - Query: `sum(rate(http_server_requests_milliseconds_count[5m]))`
   - Muestra: Promedio últimos 5 minutos
   - Unidad: RPS

10. **Rate Limit Hit % (5m)**
    - Query: `(sum(rate(...{status="429"}[5m])) / sum(rate(...[5m]))) * 100`
    - Muestra: % de rechazos últimos 5 minutos
    - Unidad: percent

11. **Heap Memory Usage %**
    - Query: `(jvm_memory_usage_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100`
    - Muestra: Porcentaje de heap usado
    - Unidad: percent

12. **Process CPU Usage %**
    - Query: `process_cpu_usage * 100`
    - Muestra: CPU actualizado en tiempo real
    - Unidad: percent

---

## Alert Rules Recomendadas

Para crear alertas automáticas en Grafana:

### Rate Limiter Alerts

| Alerta | Query | Condición | Duración | Acción |
|---|---|---|---|---|
| **High Rate Limit Hit Rate** | `(sum(...{status="429"}[5m]) / sum(...[5m])) * 100` | > 30% | 5m | Aumentar `refill-rate-per-second` |
| **API Latency High** | `histogram_quantile(0.99, ...)` | > 100ms | 5m | Investigar performance |
| **High Error Rate** | `rate(...{status="5.."}[1m])` | > 1/min | 5m | Investigar logs |

### JVM Alerts

| Alerta | Query | Condición | Duración | Acción |
|---|---|---|---|---|
| **High Heap Usage** | `(...heap} / ...max} * 100` | > 85% | 5m | Aumentar `-Xmx` |
| **GC Pause Too Long** | `histogram_quantile(0.99, jvm_gc_pause...)` | > 1s | 5m | Tuning GC |
| **Too Many Threads** | `jvm_threads_live_threads` | > 50 | 10m | Buscar memory leak |
| **High CPU Usage** | `process_cpu_usage * 100` | > 80% | 5m | Escalar horizontalmente |

---

## Exportar Métricas

### 1. Prometheus
```bash
curl http://localhost:9090/api/v1/query?query=http_server_requests_milliseconds_count | jq
```

### 2. Grafana Dashboard JSON
Panel → Menu (⋮) → **Export** → **JSON**

### 3. CSV desde Prometheus
Explore → Graph → Download CSV

---

## Correlación de Señales

En Grafana, puedes correlacionar las tres señales observables:

### Logs → Traces
1. Ve a **Explore** → **Loki**
2. Encuentra un log con `traceId`
3. Haz clic en el `traceId` → auto-navega a ese trace en Tempo

### Traces → Metrics
1. Ve a **Explore** → **Tempo**
2. Abre un trace
3. Haz clic en el **service name** → auto-filtra métricas en Prometheus

### Metrics → Logs
1. Ve a **Explore** → **Prometheus**
2. Haz clic en un pico en el gráfico
3. Panel de contexto muestra logs cercanos de Loki

---

## Optimización de Retención

### Prometheus (por defecto: 15 días)

En `prometheus.yaml`:
```yaml
global:
  scrape_interval: 10s
  # Retención total: 15 días (por defecto)
  retention:
    days: 15
```

Para cambiar:
```yaml
global:
  retention:
    days: 7      # 7 días (menor almacenamiento)
    # o
    size: 100GB  # Límite por tamaño
```

### Loki (por defecto: ilimitado)

El LGTM stack usa configuración estándar. Ver [Loki Documentation](https://grafana.com/docs/loki/latest/).

---

## Referencias

- [Micrometer Metrics](https://micrometer.io/docs/concepts)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Prometheus PromQL](https://prometheus.io/docs/prometheus/latest/querying/)
- [Grafana Alerting](https://grafana.com/docs/grafana/latest/alerting/)
- [OpenTelemetry](https://opentelemetry.io/)

