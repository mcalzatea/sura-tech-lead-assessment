#!/usr/bin/env bash
# Run all 5 failure scenarios in sequence.
# Requires: docker compose stack running (./gradlew clean build && docker compose up -d --build).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

run_scenario() {
  local name="$1"
  local script="$2"
  echo ""
  echo "══════════════════════════════════════════════"
  echo "  $name"
  echo "══════════════════════════════════════════════"
  bash "$DIR/$script"
  echo ""
  echo ">>> Resetting between scenarios..."
  bash "$DIR/reset.sh"
  sleep 2
}

run_scenario "S1 — Transient 503 (Retry + Backoff)"      s1-transient-503.sh
run_scenario "S2 — Permanent 503 (Circuit Breaker)"       s2-circuit-breaker.sh
run_scenario "S3 — Slow Upstream (Timeout)"               s3-timeout.sh
run_scenario "S4 — Intermittent Errors (Retry Smoothing)" s4-intermittent.sh
run_scenario "S5 — Idempotency Key Propagation"           s5-idempotency.sh

echo ""
echo "══════════════════════════════════════════════"
echo "  All scenarios complete."
echo "  Open observability UIs:"
echo "    Jaeger:     http://localhost:16686"
echo "    Grafana:    http://localhost:3000  (admin/admin)"
echo "    Prometheus: http://localhost:9090"
echo "══════════════════════════════════════════════"
