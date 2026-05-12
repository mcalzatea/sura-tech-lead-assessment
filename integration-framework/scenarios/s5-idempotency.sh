#!/usr/bin/env bash
# S5 — Idempotency Key Propagation + Redis Deduplication
#
# Part A: Framework auto-generates one UUID v7 key; all 3 retry attempts carry it.
# Part B: Second call with the same key is served from Redis cache (no upstream hit).
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"
DEMO="${DEMO_URL:-http://localhost:8080}"

# ── Part A: verify same key on every retry attempt ──────────────────────────

echo "==> [S5-A] Reset to healthy..."
curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

echo "==> [S5-A] Set mode: transient_5xx (N=2) — 2 failures then success..."
curl -s -X POST "$UPSTREAM/admin/mode" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":2}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S5-A] POST /orders (3 attempts: attempts 1+2 fail, attempt 3 succeeds)..."
curl -s -X POST "$DEMO/orders" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | python3 -m json.tool 2>/dev/null || true
echo ""

echo "==> [S5-A] Check flaky-upstream logs for idempotency key (expect 3 identical lines)..."
KEY=$(docker compose logs flaky-upstream 2>/dev/null \
  | grep -o '"idempotencyKey":"[^"]*"' | tail -1 | grep -o '[0-9a-f-]*$' || echo "")

if [ -n "$KEY" ]; then
  COUNT=$(docker compose logs flaky-upstream 2>/dev/null | grep -c "$KEY" || echo 0)
  echo "    Key found: $KEY"
  echo "    Occurrences in flaky-upstream logs: $COUNT (expect 3)"
else
  echo "    Could not extract key from logs. Check manually:"
  echo "    docker compose logs flaky-upstream | grep idempotencyKey"
fi
echo ""

# ── Part B: Redis cache deduplication ───────────────────────────────────────

if [ -n "$KEY" ]; then
  echo "==> [S5-B] Reset upstream to healthy..."
  curl -s -X POST "$UPSTREAM/admin/reset" > /dev/null

  echo "==> [S5-B] Repeat call with same Idempotency-Key (expect cached ACCEPTED, no upstream hit)..."
  curl -s -X POST "$DEMO/orders" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $KEY" \
    -d '{"sku":"X","qty":1}' | python3 -m json.tool 2>/dev/null || true
  echo ""

  NEW_COUNT=$(docker compose logs flaky-upstream 2>/dev/null | grep -c "$KEY" || echo 0)
  echo "==> [S5-B] flaky-upstream log count after cache hit: $NEW_COUNT (expect still 3 — no new upstream call)"
else
  echo "==> [S5-B] Skipped (could not determine key from Part A)."
fi

echo ""
echo "==> [S5] Done."
