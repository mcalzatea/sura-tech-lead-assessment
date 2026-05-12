#!/usr/bin/env bash
# S2 — Permanent 503: Circuit Breaker opens after sliding window exhaustion.
#
# Expected:
#   - First ~4 calls: FAILED with upstreamAttempts=3 (CB CLOSED, retrying).
#   - Later calls: FAILED with upstreamAttempts=0 (CB OPEN, fast-fail).
#   - integration_client_cb_state{state="OPEN"} = 1
#   - After 30s + reset to healthy + probe: CB → HALF_OPEN → CLOSED, ACCEPTED.
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"
DEMO="${DEMO_URL:-http://localhost:8080}"

echo "==> [S2] Reset to healthy..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S2] Set mode: always_503..."
curl -s -X POST "$UPSTREAM/admin/mode" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"always_503"}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S2] Sending 15 calls to exhaust sliding window (size=10, threshold=50%)..."
for i in $(seq 1 15); do
  printf "[call %2d] " "$i"
  curl -s -X POST "$DEMO/orders" \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('status=%s attempts=%s' % (r.get('status'),r.get('upstreamAttempts')))" \
  2>/dev/null || echo "(parse error)"
done
echo ""

echo "==> [S2] CB state gauge (expect OPEN=1):"
curl -s "http://localhost:9090/api/v1/query?query=integration_client_cb_state" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print('   ',r['metric'],r['value'][1]) for r in d.get('data',{}).get('result',[])]" 2>/dev/null || true
echo ""

echo "==> [S2] Waiting 31s for CB to transition to HALF_OPEN..."
sleep 31

echo "==> [S2] Resetting upstream to healthy for probe call..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S2] Probe call (expect ACCEPTED — CB HALF_OPEN → CLOSED)..."
curl -s -X POST "$DEMO/orders" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | python3 -m json.tool 2>/dev/null || true
echo ""
