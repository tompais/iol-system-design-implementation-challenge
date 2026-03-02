# Grafana Dashboards

## Descripción

Esta carpeta contiene los dashboards JSON listos para importar en Grafana. Los dashboards están diseñados para monitorear las métricas del rate limiter y la salud general de la JVM.

## Dashboards Disponibles

### `rate-limiter-metrics.json`

Dashboard comprehensivo que incluye:

#### Métricas del Rate Limiter

- **HTTP Request Latency (avg)**: Latencia promedio de las solicitudes HTTP por URI, método y estado.
- **HTTP Request Rate**: Tasa de solicitudes HTTP por segundo (RPS).
- **Rate Limit Check Requests (Total)**: Número total de solicitudes al endpoint `/api/rate-limit/check` desglosado por estado de respuesta.
- **Rate Limit Rejection Rate**: Porcentaje de solicitudes rechazadas (HTTP 429) sobre el total de solicitudes.

#### Métricas de la JVM

- **JVM Memory Usage**: Uso de memoria heap y non-heap en bytes.
- **JVM Live Threads**: Número de threads activos en la JVM.
- **Process CPU Usage**: Porcentaje de CPU usado por el proceso.
- **JVM GC Max Data Size**: Tamaño máximo de datos que gestiona el GC.

#### Indicadores de Estado (Gauges)

- **Avg Request Rate (5m)**: Tasa promedio de solicitudes en los últimos 5 minutos.
- **Rate Limit Hit % (5m)**: Porcentaje de solicitudes rechazadas en los últimos 5 minutos.
- **Heap Memory Usage %**: Porcentaje de memoria heap utilizado.
- **Process CPU Usage %**: Porcentaje de CPU utilizado.

## Cómo Importar el Dashboard

### Opción 1: Importación Manual desde Grafana UI

1. Abre Grafana en tu navegador (http://localhost:3000)
2. Ve a **Dashboards** → **+ New** → **Import**
3. Copia el contenido del archivo JSON o carga el archivo directamente
4. Selecciona la fuente de datos **Prometheus**
5. Haz clic en **Import**

### Opción 2: Usando Docker Compose (Automático)

El archivo `compose.yaml` incluye un volumen montado que permite a Grafana cargar automáticamente los dashboards:

```yaml
volumes:
  - ./grafana/dashboards:/etc/grafana/provisioning/dashboards
```

Si usas la imagen estándar de Grafana, necesitas crear un archivo de provisioning adicional.

### Opción 3: Provisioning Automático (Recomendado)

Crea el archivo `grafana/provisioning/dashboards/dashboards.yaml`:

```yaml
apiVersion: 1

providers:
  - name: 'Rate Limiter Dashboards'
    orgId: 1
    folder: 'Rate Limiter'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

Y actualiza el `compose.yaml`:

```yaml
grafana-lgtm:
  volumes:
    - ./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards
    - ./grafana/provisioning/datasources:/etc/grafana/provisioning/datasources
```

## Métricas Esperadas

### Endpoint: Prometheus `/actuator/prometheus`

Las métricas se recopilan automáticamente desde el endpoint de Prometheus de Spring Boot Actuator.

**Métricas principales disponibles:**

```
# Métricas del Rate Limiter (HTTP)
http_server_requests_seconds_bucket
http_server_requests_seconds_count
http_server_requests_seconds_sum
http_server_requests_seconds_max

# Métricas de la JVM
jvm_memory_usage_bytes
jvm_memory_max_bytes
jvm_threads_live_threads
jvm_gc_max_data_size_bytes
process_cpu_usage
process_uptime_seconds
```

## Interpretación de Métricas

### Rate Limiter

- **HTTP Request Latency**: Debe estar entre 1-10ms para operaciones normales.
- **HTTP Request Rate**: Varía según la carga. Esperado: 10-100 RPS en tests.
- **Rate Limit Rejection Rate**: Indica qué porcentaje de requests se rechaza por exceder el límite.
  - Un 0% significa que la capacidad es suficiente.
  - Un 50%+ sugiere que el límite es muy bajo o la tasa de refill insuficiente.

### JVM

- **Heap Memory Usage**: Debería estar entre 30-70% en operación normal.
  - Arriba de 85%: considera aumentar `-Xmx`.
  - Riesgos de GC pause time prolongado.

- **Live Threads**: Para una sola aplicación Spring Boot, esperado: 10-30 threads.
  - Un aumento gradual podría indicar un memory leak.

- **CPU Usage**: Debería estar <50% en carga normal.
  - Arriba de 80%: considera escalar horizontalmente.

- **GC Max Data Size**: Tamaño máximo que el GC puede manejar. Monitor para comprender el comportamiento de GC.

## Configuración de Alertas (Opcional)

En la UI de Grafana, puedes crear alertas basadas en estos paneles:

Ejemplos:

1. **Rate Limit Hit % > 30%** (5m): Alertar si más del 30% de requests se rechazan.
2. **Heap Memory > 85%**: Alertar si la memoria heap excede el 85%.
3. **CPU > 75%**: Alertar si el CPU del proceso excede el 75%.
4. **Request Latency > 100ms**: Alertar si la latencia promedio excede 100ms.

## Variables de Entorno para Métricas

En el `docker-compose.yaml`, las variables de entorno para OTLP están configuradas:

```yaml
environment:
  OTEL_EXPORTER_OTLP_ENDPOINT: http://grafana-lgtm:4317
  OTEL_SERVICE_NAME: rate-limiter
```

Nota: La imagen `grafana/otel-lgtm` incluye automáticamente Prometheus, Grafana y Loki.

## Solución de Problemas

### No aparecen métricas

1. Verifica que Prometheus esté scrapeando el endpoint:
   - Abre http://localhost:9090/targets
   - Busca el job `rate-limiter`
   - Estado debe ser `UP`

2. Verifica que las métricas existan:
   - Abre http://localhost:9090/graph
   - Escribe `http_server_requests_seconds_count` en el field
   - Debería haber resultados

3. Verifica que Grafana esté conectada a Prometheus:
   - Ve a **Configuration** → **Data Sources**
   - Busca `prometheus`
   - Haz clic en **Test**

### Métricas antiguas o desactualizadas

1. Reinicia el contenedor de la aplicación:
   ```bash
   docker compose restart app
   ```

2. Limpia los datos de Prometheus:
   ```bash
   docker compose down -v
   docker compose up -d
   ```

## Referencia de Componentes

- **Grafana**: http://localhost:3000
- **Prometheus**: http://localhost:9090
- **Aplicación**: http://localhost:8080
- **OpenAPI Docs**: http://localhost:8080/swagger-ui.html

## Más Información

- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics)
- [Micrometer Prometheus Registry](https://micrometer.io/docs/registry/prometheus)
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/)
- [OpenTelemetry](https://opentelemetry.io/)

