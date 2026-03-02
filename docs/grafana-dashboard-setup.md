# Importar el Dashboard Personalizado en Grafana

Hemos creado un dashboard personalizado (`grafana-dashboard.json`) que está optimizado específicamente para las métricas de tu rate-limiter.

## Pasos para importar:

### 1. Inicia el stack LGTM

```bash
docker compose up
```

### 2. Abre Grafana

- URL: http://localhost:3000
- Usuario: `admin`
- Contraseña: `admin`

### 3. Importa el dashboard

1. En Grafana, ve a: **Dashboards** (sidebar izquierdo)
2. Haz clic en **+ New** → **Import**
3. Selecciona una de estas opciones:

   **Opción A: Upload JSON file**
   - Haz clic en "Upload JSON file"
   - Selecciona el archivo `grafana-dashboard.json` del proyecto
   - Haz clic en "Import"

   **Opción B: Paste JSON (si está en el navegador)**
   - Abre `grafana-dashboard.json` en un editor de texto
   - Copia todo el contenido JSON
   - En el import dialog de Grafana, ve a la pestaña de editor
   - Pega el JSON completo
   - Haz clic en "Load"

4. En la siguiente pantalla:
   - **Name**: Rate Limiter - Prometheus Metrics
   - **Folder**: General
   - **Datasource**: Selecciona **Prometheus**
   - Haz clic en **Import**

### 4. Genera tráfico para ver métricas

```bash
# En otra terminal, genera requests
for i in {1..50}; do
  curl -X POST http://localhost:8080/api/rate-limit/check \
    -H 'Content-Type: application/json' \
    -d '{"key":"test-user"}'
  sleep 0.1
done
```

### 5. Visualiza el dashboard

Deberías ver inmediatamente:
- ✅ **Request Rate** - aumenta mientras haces requests
- ✅ **429 Rate** - aumenta después de 10 requests (agotaste el bucket)
- ✅ **Request Duration** - tiempo de respuesta en percentiles
- ✅ **JVM Heap Memory** - uso de memoria
- ✅ **JVM Live Threads** - cantidad de threads activos
- ✅ **Garbage Collection** - pausas de GC
- ✅ **Request by Status Code** - desglose 200/429/400

---

## ¿Qué hace este dashboard?

Este dashboard personalizado monitorea específicamente:

### Panel 1: Request Rate
```promql
rate(http_server_requests_seconds_count{uri="/api/rate-limit/check"}[1m])
```
Muestra cuántos requests por segundo estás procesando.

### Panel 2: 429 Rate (Rate Limit Exceeded)
```promql
rate(http_server_requests_seconds_count{uri="/api/rate-limit/check",status="429"}[1m])
```
Muestra cuántas solicitudes fueron rechazadas por exceder el rate limit.

### Panel 3: Request Duration (Percentiles)
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri="/api/rate-limit/check"}[1m]))
```
Latencia p95 y p50 de las requests.

### Panel 4: JVM Heap Memory
```promql
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```
Porcentaje de heap usado (0-100%).

### Panel 5: JVM Threads
```promql
jvm_threads_live
```
Cantidad de threads activos en la JVM.

### Panel 6: Garbage Collection
```promql
jvm_gc_pause_seconds * 1000
```
Duración de las pausas de GC en milisegundos.

### Panel 7: Request Breakdown by Status
```promql
sum by (status) (rate(http_server_requests_seconds_count{uri="/api/rate-limit/check"}[1m]))
```
Distribución de requests por código de estado (200, 400, 429).

---

## Troubleshooting

### Las métricas muestran "No data"

1. **Verifica que Prometheus está scrapeando:**
   - Ve a http://localhost:9090
   - Busca en el selector de métricas: `http_server_requests_seconds_count`
   - Si aparece, Prometheus está recibiendo datos

2. **Verifica que la app expone métricas:**
   ```bash
   curl http://localhost:8080/actuator/prometheus | grep http_server_requests
   ```
   Deberías ver líneas con métricas.

3. **Espera 30 segundos:**
   - Prometheus scrape cada 10s
   - Grafana refresca cada 10s
   - Algunos paneles necesitan datos históricos

4. **Haz más requests:**
   ```bash
   for i in {1..100}; do
     curl -s -X POST http://localhost:8080/api/rate-limit/check \
       -H 'Content-Type: application/json' \
       -d '{"key":"test"}' > /dev/null
     sleep 0.05
   done
   ```

### El datasource Prometheus no aparece

- Ve a Configuration → Data Sources
- Deberías ver "prometheus" en la lista
- Si no está, crea uno nuevo:
  - Type: Prometheus
  - URL: http://localhost:9090 (dentro del Docker) o http://grafana-lgtm:9090
  - Save

---

## JSON del Dashboard

El archivo `grafana-dashboard.json` contiene:
- 7 paneles específicos para rate-limiter
- Configuración de colores y umbrales
- Refresh automático cada 10 segundos
- Timerange por defecto: últimos 5 minutos

Si necesitas modificarlo:
- Edita el dashboard en Grafana
- Exporta como JSON (arriba a la derecha: Dashboard settings → JSON Model)
- Guarda el JSON actualizado

---

## Siguientes pasos

Una vez que confirmes que las métricas aparecen:

1. **Agregar alertas** (opcional):
   - Rate limit exceeded rate > 5 req/sec
   - JVM heap > 85%
   - Request duration p95 > 100ms

2. **Persistir el dashboard**:
   - El dashboard se guarda en Grafana
   - Pero si eliminás el volumen de Grafana, se pierde
   - Solución: exportar como JSON regularmente

3. **Agregar logs** (si quieres trazas completas):
   - Loki ya está en el stack LGTM
   - Necesitarías agregar un appender Log4j2 para Loki (opcional)


