# Quick Start - Observabilidad y Dashboards

## TL;DR - 3 pasos

```bash
# 1. Inicia el stack completo (app + observabilidad)
./gradlew bootRun

# 2. Abre Grafana
open http://localhost:3000

# 3. Ve al dashboard "Rate Limiter - Metrics & System Performance"
# (Dashboards → Rate Limiter → Rate Limiter - Metrics & System Performance)
```

## URLs Importantes

| Servicio | URL | Credenciales |
|---|---|---|
| **App** | http://localhost:8080 | — |
| **OpenAPI Docs** | http://localhost:8080/swagger-ui.html | — |
| **Prometheus** | http://localhost:9090 | — |
| **Grafana** | http://localhost:3000 | admin/admin |
| **Logs (Loki)** | Explore → Loki en Grafana | — |
| **Traces (Tempo)** | Explore → Tempo en Grafana | — |
| **Métricas** | Explore → Prometheus en Grafana | — |

## Componentes Incluidos

### 🚀 Aplicación
- **Spring Boot 4.0.3** + WebFlux Functional
- **Rate Limiter Token Bucket** con CAS
- **OpenTelemetry** automático

### 📊 Observabilidad (Docker Compose)
- **Prometheus**: Scraping cada 10 segundos de `/actuator/prometheus`
- **Grafana**: Dashboard pre-configurado con 12 paneles
- **Tempo**: Traces distribuidos (OTLP gRPC)
- **Loki**: Agregación de logs (Log4j2 → stdout)
- **OpenTelemetry Collector**: OTLP receiver

## Verificar que está funcionando

### 1. Health Check

```bash
curl http://localhost:8080/actuator/health | jq
# → {"status":"UP","components":...}
```

### 2. Probar Rate Limiter

```bash
# Primer request (permitido)
curl -X POST http://localhost:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"user-1"}'
# → 200 {"allowed":true}

# Exhaust bucket (10 más + 1 rechazado con capacity=10)
for i in {1..11}; do
  curl -s -X POST http://localhost:8080/api/rate-limit/check \
    -H 'Content-Type: application/json' \
    -d '{"key":"user-1"}' | jq .allowed
done
# → true ×10, false ×1
```

### 3. Verificar Métricas en Prometheus

```bash
curl http://localhost:9090/api/v1/query?query=http_server_requests_milliseconds_count | jq
# → Debes ver resultados con las métricas de tus requests
```

## Dashboard

El dashboard **"Rate Limiter - Metrics & System Performance"** incluye:

### Paneles Timeseries
1. **HTTP Request Latency (avg - ms)** - Latencia promedio en milisegundos
2. **HTTP Request Rate** - Requests por segundo (RPS)
3. **Rate Limit Check Requests (Total)** - Total de requests procesados
4. **Rate Limit Rejection Rate** - Porcentaje de requests rechazados (HTTP 429)
5. **JVM Memory Usage** - Heap y non-heap memory
6. **JVM Live Threads** - Threads activos
7. **Process CPU Usage** - CPU del proceso
8. **JVM GC Max Data Size** - Tamaño máximo GC

### Paneles Gauge (KPIs)
9. **Avg Request Rate (5m)** - RPS promedio en los últimos 5 minutos
10. **Rate Limit Hit % (5m)** - % de rechazos en los últimos 5 minutos
11. **Heap Memory Usage %** - Porcentaje de heap usada
12. **Process CPU Usage %** - Porcentaje de CPU del proceso

## Troubleshooting

### Las métricas no aparecen

```bash
# 1. Verifica que Prometheus está scrapeando la app
curl http://localhost:9090/targets

# 2. Verifica que la métrica existe en Prometheus
curl http://localhost:9090/api/v1/query?query=http_server_requests_milliseconds_count

# 3. Si aún no aparece, espera 30s (intervalo de scrape + delay)
```

### El dashboard está vacío en Grafana

1. Abre http://localhost:3000/dashboard/edit (editar modo)
2. Verifica que el datasource "Prometheus" existe:
   - Configuration → Data Sources → Prometheus → Test
3. Si está rojo, verifica que Prometheus corre en `http://grafana-lgtm:9090`

### Logs no aparecen en Loki

- Los logs se envían a stdout del contenedor
- Loki los ingesta automáticamente
- Busca por: `{job="rate-limiter"}` en Explore → Loki

## Siguiente paso

Para entender la arquitectura completa de observabilidad:

→ Ver [`docs/observability.md`](./observability.md)

Para ver todas las métricas disponibles y queries PromQL:

→ Ver [`docs/metrics-reference.md`](./metrics-reference.md)

