#!/usr/bin/env bash
# S1 — Transient 503: Retry + Exponential Backoff + Jitter
#
# Expected:
#   - 3 failing attempts (503), then 1 successful attempt.
#   - Response: { status: "ACCEPTED", upstreamAttempts: 4 }
#   - Logs show 3 retry events with increasing delay_ms.
#   - Prometheus: integration_client_retries_total{target="flaky-upstream"} += 3
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"
DEMO="${DEMO_URL:-http://localhost:8080}"

echo "==> [S1] Reset to healthy..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S1] Set mode: transient_5xx (N=3)..."
curl -s -X POST "$UPSTREAM/admin/mode" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":3}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S1] POST /orders (expect ACCEPTED after 4 attempts)..."
curl -s -X POST "$DEMO/orders" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | python3 -m json.tool 2>/dev/null || \
  curl -s -X POST "$DEMO/orders" -H 'Content-Type: application/json' -d '{"sku":"X","qty":1}'
echo ""

echo "==> [S1] Verify retries metric:"
echo "    integration_client_retries_total{target=\"flaky-upstream\"}"
curl -s "http://localhost:9090/api/v1/query?query=integration_client_retries_total" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print('   ',r['metric'],r['value'][1]) for r in d.get('data',{}).get('result',[])]" 2>/dev/null || true
echo ""
