#!/bin/bash

# Verification script for Rate Limiter observability stack
# This script checks every component of the metrics pipeline

set -e  # Exit on error

echo "=================================="
echo "Rate Limiter - Observability Check"
echo "=================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

echo "Step 1: Checking if containers are running..."
echo "=============================================="
if docker ps | grep -q "rate-limiter-app"; then
    print_success "rate-limiter-app is running"
else
    print_error "rate-limiter-app is NOT running"
    echo "Run: docker compose up --build"
    exit 1
fi

if docker ps | grep -q "prometheus"; then
    print_success "prometheus is running"
else
    print_error "prometheus is NOT running"
    exit 1
fi

if docker ps | grep -q "grafana-lgtm"; then
    print_success "grafana-lgtm is running"
else
    print_error "grafana-lgtm is NOT running"
    exit 1
fi

echo ""
echo "Step 2: Checking app health..."
echo "==============================="
if curl -s -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
    print_success "App health endpoint is responding"
else
    print_error "App health endpoint is NOT responding"
    echo "Check logs: docker logs rate-limiter-app"
    exit 1
fi

echo ""
echo "Step 3: Checking if app exposes Prometheus metrics..."
echo "======================================================"
if curl -s http://localhost:8080/actuator/prometheus | grep -q "http_server_requests_seconds_count"; then
    print_success "App exposes Prometheus metrics at /actuator/prometheus"
    echo "Sample metrics:"
    curl -s http://localhost:8080/actuator/prometheus | grep "http_server_requests_seconds_count" | head -3
else
    print_error "App does NOT expose Prometheus metrics"
    echo "Check application.yaml: management.endpoints.web.exposure.include"
    exit 1
fi

echo ""
echo "Step 4: Checking Prometheus can reach the app..."
echo "================================================="
if docker exec prometheus wget -q -O - http://app:8080/actuator/prometheus | grep -q "http_server_requests"; then
    print_success "Prometheus CAN reach app:8080/actuator/prometheus (Docker DNS working)"
else
    print_error "Prometheus CANNOT reach app via Docker DNS"
    echo "Check docker network: docker network inspect monitoring"
    exit 1
fi

echo ""
echo "Step 5: Checking Prometheus targets..."
echo "========================================"
TARGETS=$(curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | select(.labels.job=="rate-limiter") | .health')
if [ "$TARGETS" == "up" ]; then
    print_success "Prometheus target 'rate-limiter' is UP"
else
    print_error "Prometheus target 'rate-limiter' is DOWN or missing"
    echo "Check http://localhost:9090/targets"
    echo "Check prometheus.yml configuration"
    exit 1
fi

echo ""
echo "Step 6: Checking if Prometheus has metrics data..."
echo "==================================================="
METRIC_COUNT=$(curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes' | jq -r '.data.result | length')
if [ "$METRIC_COUNT" -gt 0 ]; then
    print_success "Prometheus has metrics data ($METRIC_COUNT data points)"
else
    print_error "Prometheus has NO metrics data"
    echo "Wait 30 seconds for first scrape, then try again"
    exit 1
fi

echo ""
echo "Step 7: Checking Zipkin tracing configuration..."
echo "=================================================="
ZIPKIN_ENDPOINT=$(docker exec rate-limiter-app env | grep "MANAGEMENT_ZIPKIN_TRACING_ENDPOINT" || echo "NOT_SET")
if echo "$ZIPKIN_ENDPOINT" | grep -q "http://grafana-lgtm:9411"; then
    print_success "Zipkin endpoint is correctly configured: $ZIPKIN_ENDPOINT"
else
    print_error "Zipkin endpoint is WRONG or missing: $ZIPKIN_ENDPOINT"
    echo "Should be: http://grafana-lgtm:9411/api/v2/spans"
    exit 1
fi

echo ""
echo "Step 8: Checking for errors in app logs..."
echo "==========================================="
if docker logs rate-limiter-app 2>&1 | grep -i "connection refused\|failed to publish\|otlp" | tail -5; then
    print_warning "Found errors in app logs (see above)"
    echo "These might be harmless startup errors or real issues"
else
    print_success "No connection errors in app logs"
fi

echo ""
echo "Step 9: Generating test traffic..."
echo "===================================="
print_warning "Generating 20 requests to create metrics..."
for i in {1..20}; do
    curl -s -X POST http://localhost:8080/api/rate-limit/check \
        -H 'Content-Type: application/json' \
        -d '{"key":"test-user"}' > /dev/null 2>&1
    sleep 0.1
done
print_success "Test traffic generated"

echo ""
echo "Step 10: Verifying metrics after traffic..."
echo "============================================="
sleep 5  # Wait for Prometheus scrape
METRIC_COUNT_AFTER=$(curl -s 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count{uri="/api/rate-limit/check"}' | jq -r '.data.result | length')
if [ "$METRIC_COUNT_AFTER" -gt 0 ]; then
    print_success "HTTP metrics are being collected ($METRIC_COUNT_AFTER series)"
    echo "Metric sample:"
    curl -s 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count{uri="/api/rate-limit/check"}' | jq -r '.data.result[0]'
else
    print_error "HTTP metrics are NOT being collected after traffic"
    echo "Check Prometheus config and scrape interval"
    exit 1
fi

echo ""
echo "=================================="
echo "ALL CHECKS PASSED! ✓"
echo "=================================="
echo ""
echo "Next steps:"
echo "1. Open Grafana: http://localhost:3000 (admin/admin)"
echo "2. Go to Dashboards → Import"
echo "3. Upload grafana-dashboard.json"
echo "4. Select Prometheus datasource"
echo "5. You should see metrics in real-time!"
echo ""
echo "URLs:"
echo "  - Grafana: http://localhost:3000"
echo "  - Prometheus: http://localhost:9090"
echo "  - App: http://localhost:8080"
echo ""
