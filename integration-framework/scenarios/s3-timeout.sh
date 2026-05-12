#!/usr/bin/env bash
# S3 — Slow Upstream: TimeLimiter fires before upstream responds.
#
# Expected:
#   - demo-service read timeout = 4s; upstream sleeps 10s.
#   - Each attempt times out at ~4s → 3 attempts → ~12–18s total.
#   - Response: { status: "FAILED", upstreamAttempts: 3 }
#   - error_class contains "Timeout" in logs.
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"
DEMO="${DEMO_URL:-http://localhost:8080}"

echo "==> [S3] Reset to healthy..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S3] Set mode: latency_ms (10 000 ms — well above 4s read timeout)..."
curl -s -X POST "$UPSTREAM/admin/mode" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"latency_ms","value":10000}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S3] POST /orders (expect ~12–18s total, status FAILED)..."
time curl -s -X POST "$DEMO/orders" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S3] Resetting upstream..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null
