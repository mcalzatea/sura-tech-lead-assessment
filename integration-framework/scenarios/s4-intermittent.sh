#!/usr/bin/env bash
# S4 — Intermittent Errors: Retry smoothing absorbs 30% per-attempt failure rate.
#
# Expected:
#   - P(all 3 attempts fail) = 0.3^3 ≈ 2.7%.
#   - ≥18 / 20 calls return ACCEPTED.
#   - CB stays CLOSED (successes dominate the sliding window).
#   - integration_client_retries_total > 0 (transparent retries happened).
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"
DEMO="${DEMO_URL:-http://localhost:8080}"

echo "==> [S4] Reset to healthy..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S4] Set mode: intermittent_p (p=0.3)..."
curl -s -X POST "$UPSTREAM/admin/mode" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"intermittent_p","value":0.3}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S4] Sending 20 calls (expect ≥18 ACCEPTED)..."
accepted=0
failed=0
for i in $(seq 1 20); do
  result=$(curl -s -X POST "$DEMO/orders" \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r.get('status','?'))" 2>/dev/null || echo "ERROR")
  printf "[call %2d] %s\n" "$i" "$result"
  if [ "$result" = "ACCEPTED" ]; then ((accepted++)) || true; else ((failed++)) || true; fi
done
echo ""
echo "==> [S4] Results: ACCEPTED=$accepted  FAILED=$failed (out of 20)"
echo ""

echo "==> [S4] CB state (expect OPEN=0):"
curl -s "http://localhost:9090/api/v1/query?query=integration_client_cb_state%7Bstate%3D%22OPEN%22%7D" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); r=d.get('data',{}).get('result',[]); print('   OPEN =', r[0]['value'][1] if r else '0 (no data yet)')" 2>/dev/null || true

echo ""
echo "==> [S4] Retries total:"
curl -s "http://localhost:9090/api/v1/query?query=integration_client_retries_total" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print('   ',r['metric'],r['value'][1]) for r in d.get('data',{}).get('result',[])]" 2>/dev/null || true
echo ""

echo "==> [S4] Resetting upstream..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null
