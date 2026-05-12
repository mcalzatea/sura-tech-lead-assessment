#!/usr/bin/env bash
# Reset flaky-upstream to healthy mode between scenarios.
set -euo pipefail

UPSTREAM="${UPSTREAM_URL:-http://localhost:8081}"

echo "==> Resetting flaky-upstream to HEALTHY..."
curl -s -X POST "$UPSTREAM/admin/reset" | python3 -m json.tool 2>/dev/null || \
  curl -s -X POST "$UPSTREAM/admin/reset"
echo ""
