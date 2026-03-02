# Observability Stack - Metrics & Tracing Setup

## Overview

This document explains the observability configuration for the rate-limiter project using the LGTM stack (Loki, Grafana, Tempo, Prometheus).

---

## Architecture

### Metrics Flow

```
Rate-Limiter App
       ↓
Micrometer (Spring Boot)
       ↓
Prometheus Registry (/actuator/prometheus)
       ↓
Prometheus Scraper (in Grafana-LGTM)
       ↓
Grafana Dashboards
```

### Tracing Flow

```
Rate-Limiter App
       ↓
Brave (Micrometer Tracing)
       ↓
Zipkin API (port 9411)
       ↓
Grafana Tempo (trace storage)
       ↓
Grafana Explore (trace visualization)
```

---

## Configuration Details

### 1. Metrics Export: Prometheus Registry

**File:** `application.yaml`

```yaml
management:
  prometheus:
    metrics:
      export:
        step: 10s  # Metrics are calculated every 10 seconds
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus  # Expose /actuator/prometheus endpoint
```

**Why Prometheus Registry?**
- Spring Boot generates metrics in Prometheus text format at `/actuator/prometheus`
- Prometheus scrapes this endpoint periodically (every 10s as per `prometheus.yml`)
- Grafana reads from Prometheus to display dashboards
- This is the standard pattern for Spring Boot + Prometheus + Grafana

### 2. Distributed Tracing: Brave → Zipkin

**File:** `application.yaml`

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # Sample 100% of requests (for development)
  otlp:
    tracing:
      endpoint: http://localhost:9411  # Zipkin endpoint (Brave reporter sends traces here)
```

**Why Brave + Zipkin?**
- `spring-boot-micrometer-tracing-brave` provides Brave instrumentation
- Brave automatically creates spans for HTTP requests, database calls, etc.
- Traces are sent to Zipkin endpoint (port 9411) in LGTM stack
- Each request gets a unique `traceId` for end-to-end tracing

### 3. Docker Compose Setup

**File:** `compose.yaml`

```yaml
grafana-lgtm:
  volumes:
    - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro  # Mount scrape config
```

This mounts our `prometheus.yml` config into the Grafana-LGTM container so Prometheus knows to scrape the app.

### 4. Prometheus Scrape Configuration

**File:** `prometheus.yml`

```yaml
scrape_configs:
  - job_name: 'sd-implementation-challenge'
    static_configs:
      - targets: ['app:8080']  # Docker service name + port
    metrics_path: '/actuator/prometheus'  # Spring Boot metrics endpoint
    scrape_interval: 10s
```

This tells Prometheus to:
1. Connect to `app:8080` (Docker internal network)
2. Fetch metrics from `/actuator/prometheus`
3. Store them with job label `sd-implementation-challenge`
4. Repeat every 10 seconds

---

## What Metrics Are Available?

After the app handles requests, you'll see metrics like:

### HTTP Metrics (from Spring WebFlux)
```
http_server_requests_seconds_count  # Total requests
http_server_requests_seconds_sum    # Request time (seconds)
http_server_requests_seconds_max    # Max request time
```

### JVM Metrics (automatic)
```
jvm_memory_used_bytes      # Current heap memory
jvm_memory_max_bytes       # Max heap memory
jvm_threads_live           # Live thread count
jvm_gc_memory_promoted_bytes  # Garbage collection
process_cpu_usage          # CPU utilization
```

### Application Metrics (custom, if added)
```
rate_limiter_tokens_remaining  # If we add custom metrics
rate_limiter_check_duration    # If we add custom metrics
```

---

## How to View Metrics

### 1. Via Prometheus UI
- URL: `http://localhost:9090`
- Search for metric names like `jvm_memory_used_bytes`
- Build custom queries in PromQL

### 2. Via Grafana Dashboards
- URL: `http://localhost:3000`
- Import or create dashboards using Prometheus datasource
- Use the provided `dashboard.json` (JVM Overview)

### 3. Via Application Metrics Endpoint
- URL: `http://localhost:8080/actuator/prometheus`
- Raw Prometheus text format
- Useful for quick debugging

---

## Troubleshooting

### No metrics appearing in Grafana?

1. **Check Prometheus is scraping:**
   ```bash
   # Open http://localhost:9090/targets
   # Look for job 'sd-implementation-challenge' with status "UP"
   ```

2. **Check app is exposing metrics:**
   ```bash
   curl http://localhost:8080/actuator/prometheus | head -20
   # Should show metric lines starting with #
   ```

3. **Check Prometheus config:**
   ```bash
   # Inside Docker container
   docker exec grafana-lgtm cat /etc/prometheus/prometheus.yml
   # Should show our scrape_configs
   ```

4. **Check network connectivity:**
   ```bash
   docker exec grafana-lgtm ping app
   # Should respond (app is the Docker service name)
   ```

### No traces appearing?

1. **Check Zipkin endpoint:**
   ```bash
   curl http://localhost:9411/api/v2/spans
   # Should return [] if no traces, or [{"traceId":"...", ...}] if traces exist
   ```

2. **Check app is sending traces:**
   ```bash
   docker compose logs app | grep -i "sending.*spans"
   # Should show trace export logs
   ```

---

## Key Differences from Previous Setup

| Before | After | Reason |
|--------|-------|--------|
| OTLP metrics export to port 4318 | Prometheus Registry at `/actuator/prometheus` | Conflicts resolved; cleaner separation of concerns |
| No Prometheus scrape config | `prometheus.yml` mounted in compose | Explicit config for clarity and reproducibility |
| Mixed OTLP/Prometheus | Only Prometheus for metrics, OTLP for tracing | Metrics via pull (Prometheus), traces via push (Brave) |
| Traces to port 4317 (gRPC) | Traces to port 9411 (Zipkin/REST) | Simpler, more compatible with LGTM stack |

---

## Performance Notes

- **Sampling**: Set to 100% (`probability: 1.0`) for development. For production, reduce to 5-10% to avoid overhead.
- **Scrape interval**: Set to 10s. Increase if metrics cardinality becomes an issue.
- **Retention**: Prometheus stores data in-memory. Check container limits if needed.

---

## Next Steps

1. **Custom metrics** (optional): Add business metrics like `rate_limiter_denials_total`, `rate_limiter_refill_duration`
2. **Alerts** (optional): Define Prometheus alert rules for SLA violations
3. **Distributed context propagation**: Ensure `traceId` appears in logs (requires `TraceIdLoggingFilter`)


