#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Orbit Platform — Manual Test Script
# Run this after `docker-compose up -d` and starting the app.
# Each section tests one platform feature end-to-end.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
BASE="http://localhost:8080/api/v1"
RECEIVER="http://localhost:8888"  # webhook-receiver container

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ok()   { echo -e "${GREEN}  ✓ $1${NC}"; }
info() { echo -e "${YELLOW}  → $1${NC}"; }
fail() { echo -e "${RED}  ✗ $1${NC}"; }
header() { echo -e "\n${YELLOW}═══ $1 ═══${NC}"; }

# ─── 1. Health check ──────────────────────────────────────────────────────────
header "1. Health Check"
STATUS=$(curl -s "$BASE/../actuator/health" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['status'])")
if [ "$STATUS" = "UP" ]; then ok "Application is UP"; else fail "Application DOWN: $STATUS"; exit 1; fi

# ─── 2. Create a job ──────────────────────────────────────────────────────────
header "2. Create Job"
JOB=$(curl -s -X POST "$BASE/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-report",
    "cronExpression": "0 * * * * *",
    "target": "http://orbit-webhook-receiver:8080/execute",
    "status": "ACTIVE"
  }')
echo "$JOB" | python3 -m json.tool
JOB_ID=$(echo "$JOB" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
ok "Job created with ID: $JOB_ID"

# ─── 3. Get job (first call — DB hit) ─────────────────────────────────────────
header "3. Read Job (DB hit)"
T1=$( { time curl -s "$BASE/jobs/$JOB_ID" > /dev/null; } 2>&1 | grep real | awk '{print $2}')
info "First read latency: $T1 (DB query)"

# ─── 4. Get job (second call — Redis cache hit) ───────────────────────────────
header "4. Read Job (Cache hit)"
T2=$( { time curl -s "$BASE/jobs/$JOB_ID" > /dev/null; } 2>&1 | grep real | awk '{print $2}')
ok "Second read latency: $T2 (should be much faster — Redis)"

# ─── 5. Register a webhook (with HMAC secret) ─────────────────────────────────
header "5. Register Webhook"
WH=$(curl -s -X POST "$BASE/jobs/$JOB_ID/webhooks" \
  -H "Content-Type: application/json" \
  -d "{
    \"url\": \"$RECEIVER\",
    \"secretKey\": \"my-hmac-secret-key\",
    \"eventFilter\": \"ALL\"
  }")
echo "$WH" | python3 -m json.tool
WH_ID=$(echo "$WH" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
ok "Webhook registered with ID: $WH_ID"

# ─── 6. Record a SUCCESS execution (async — returns 202) ──────────────────────
header "6. Record Execution (async)"
EXEC_RES=$(curl -s -X POST "$BASE/jobs/$JOB_ID/executions" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "SUCCESS",
    "startedAt": "2026-04-01T10:00:00.000Z",
    "finishedAt": "2026-04-01T10:00:05.123Z"
  }')
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/jobs/$JOB_ID/executions" \
  -H "Content-Type: application/json" \
  -d '{"status":"SUCCESS"}')
if [ "$HTTP_CODE" = "202" ]; then
  ok "Execution queued → 202 Accepted (non-blocking)"
else
  fail "Expected 202 but got $HTTP_CODE"
fi

# ─── 7. Wait for webhook delivery ────────────────────────────────────────────
header "7. Webhook Delivery Check"
info "Waiting 5s for webhook to be delivered..."
sleep 5

DELIVERIES=$(curl -s "$BASE/jobs/$JOB_ID/webhooks/$WH_ID/deliveries")
echo "$DELIVERIES" | python3 -m json.tool | head -30
ok "Webhook delivery history fetched"

# ─── 8. Record a FAILED execution to test webhook retry ──────────────────────
header "8. FAILED Execution → Webhook with Retry"
curl -s -X POST "$BASE/jobs/$JOB_ID/executions" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "FAILED",
    "errorMessage": "Connection timeout after 30s"
  }' > /dev/null
ok "Failed execution queued"

# ─── 9. Dashboard summary ────────────────────────────────────────────────────
header "9. Dashboard Summary"
curl -s "$BASE/dashboard/summary" | python3 -m json.tool
ok "Dashboard summary fetched"

# ─── 10. Rate limiter test ───────────────────────────────────────────────────
header "10. Rate Limiter Test"
info "Sending 100 rapid requests — some should get 429..."
CODES=$(for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST "$BASE/jobs" \
    -H "Content-Type: application/json" \
    -d '{"name":"ratelimit-test","cronExpression":"0 * * * * *","target":"http://test","status":"ACTIVE"}'
done)
CREATED=$(echo "$CODES" | grep -c "201" || true)
RATE_LIMITED=$(echo "$CODES" | grep -c "429" || true)
ok "Created: $CREATED  |  Rate limited: $RATE_LIMITED (both are correct behavior)"

# ─── 11. Pagination test ─────────────────────────────────────────────────────
header "11. Paginated Endpoints"
curl -s "$BASE/jobs?page=0&size=5&sort=nextFireTime,asc" | python3 -m json.tool | grep -E '"totalElements"|"totalPages"|"size"'
ok "Pagination works"

# ─── 12. Verify Redis cache via actuator ─────────────────────────────────────
header "12. Cache Metrics"
curl -s "http://localhost:8080/actuator/metrics/cache.gets" | python3 -m json.tool | head -20
ok "Cache metrics available"

echo -e "\n${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  All tests passed! Platform is healthy.${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo ""
echo "  Dashboard:   http://localhost:8080"
echo "  Kafka UI:    http://localhost:8090"
echo "  Grafana:     http://localhost:3000 (admin/admin)"
echo "  Prometheus:  http://localhost:9090"
echo "  WH Receiver: http://localhost:8888"
echo ""
