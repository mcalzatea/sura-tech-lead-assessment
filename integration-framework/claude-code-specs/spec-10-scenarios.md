# Spec 10 — Failure Scenarios (S1–S5)

## Context
Scripts and expected behavior for each failure scenario. Each scenario follows the same
structure: setup command → trigger command → expected observable evidence (logs, traces,
metrics). All commands assume the full Docker Compose stack is running.

**Depends on**: spec-09 (stack running), spec-07 (flaky-upstream admin API),
spec-08 (demo-service `/orders` endpoint).

---

## Reset helper

Always reset between scenarios:

```bash
curl -s -X POST http://localhost:8081/admin/reset
# Expected: {"mode":"healthy"}
```

---

## S1 — Transient 503 (Retry + Exponential Backoff + Jitter)

### Setup
```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":3}'
# Expected: {"mode":"transient_5xx","intValue":3,"callCount":0}
```

### Trigger
```bash
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}'
```

### Expected response
```json
{
  "orderId": "<uuid>",
  "status": "ACCEPTED",
  "upstreamAttempts": 4,
  "latencyMs": "<sum of 3 jittered backoffs + 4 call latencies>",
  "traceId": "<hex>"
}
```

### Expected log evidence (demo-service JSON logs)
```
event: integration.call.start     attempt: 1
event: integration.call.retry     attempt: 2   delay_ms: ~200–400
event: integration.call.retry     attempt: 3   delay_ms: ~200–800
event: integration.call.retry     attempt: 4   delay_ms: ~200–2000
event: integration.call.success   attempt_count: 4   status_code: 200
```

### Expected Jaeger trace
- One root span `HTTP POST flaky-upstream/work`.
- 4 child spans (one per attempt); first 3 marked ERROR (503), last marked OK.
- `integration.attempt_count = 4` attribute on root span.

### Expected Prometheus metrics
- `integration_client_calls_total{target="flaky-upstream",outcome="success"}` += 1
- `integration_client_retries_total{target="flaky-upstream"}` += 3

---

## S2 — Permanent 503 (Circuit Breaker)

### Setup
```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"always_503"}'
```

### Trigger — exhaust sliding window to open CB
```bash
for i in $(seq 1 15); do
  curl -s -X POST http://localhost:8080/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}'
  echo ""
done
```

### Expected response (first calls — CB CLOSED, retrying)
```json
{ "status": "FAILED", "upstreamAttempts": 3, "traceId": "..." }
```

### Expected response (after CB opens)
```json
{ "status": "FAILED", "upstreamAttempts": 0, "traceId": "..." }
```
Note: `upstreamAttempts: 0` because CB rejects before any HTTP call is made.

### Expected log evidence
```
# While CB CLOSED:
event: integration.call.start
event: integration.call.retry   attempt: 2
event: integration.call.retry   attempt: 3
event: integration.call.failure error_class: "IntegrationException"

# After CB OPEN:
event: integration.call.start
event: integration.call.failure error_class: "CallNotPermittedException"
```

### Expected Prometheus metrics
- `integration_client_cb_state{target="flaky-upstream",state="OPEN"}` = 1
- `integration_client_calls_total{outcome="cb_open"}` increments on fast-fail calls

### CB half-open probe (wait 30s after CB opens)
```bash
# Wait for wait-duration-in-open-state (30s default)
sleep 31

# Reset upstream to healthy so probe succeeds
curl -s -X POST http://localhost:8081/admin/reset

# Send one probe call
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}'
# Expected: ACCEPTED — CB transitions HALF_OPEN → CLOSED
```

---

## S3 — Slow Upstream (Timeout + Retry)

### Setup

`demo-service` config has `read: 4s`. Set upstream to sleep 10s (well above timeout):

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"latency_ms","value":10000}'
```

### Trigger
```bash
time curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}'
```

### Expected response
```json
{ "status": "FAILED", "upstreamAttempts": 3, "traceId": "..." }
```

### Expected timing
- Each attempt times out at ~4 s.
- Total elapsed: `time` output ≈ 3 × 4 s + backoff delays ≈ 13–16 s.

### Expected log evidence
```
event: integration.call.start
event: integration.call.retry   attempt: 2   error_class: "TimeoutException"
event: integration.call.retry   attempt: 3   error_class: "TimeoutException"
event: integration.call.failure error_class: "TimeoutException"   attempt_count: 3
```

### Expected Jaeger trace
- 3 child spans all marked ERROR with `exception.type = TimeoutException`.

---

## S4 — Intermittent Errors (Retry Smoothing)

### Setup
```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"intermittent_p","value":0.3}'
```

### Trigger — 20 calls
```bash
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}'
  echo ""
done
```

### Expected behavior
- With 30% failure probability per attempt and up to 3 retries, the probability that
  all 3 attempts fail = 0.3³ ≈ 2.7%. Client sees near 100% success across 20 calls.
- CB does NOT open because successes outnumber failures within the sliding window.

### Expected Prometheus metrics
- `integration_client_calls_total{outcome="success"}` ≈ 20 (all or nearly all succeed).
- `integration_client_retries_total` > 0 (some retries happen transparently).
- `integration_client_cb_state{state="OPEN"}` = 0 (CB stays CLOSED).

---

## S5 — Idempotency Key Propagation

### Setup
```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":2}'
```

### Trigger
```bash
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}'
```

### Expected behavior
- Framework auto-generates one `Idempotency-Key` (UUID v7) before the first attempt.
- All 3 attempts (2 failing + 1 succeeding) carry the **same** key value.
- `flaky-upstream` logs show the same key 3 times.

### Verification — check flaky-upstream logs
```bash
docker compose logs flaky-upstream | grep idempotencyKey
```

Expected output (3 lines with identical key):
```
{"event":"upstream.work.called","idempotencyKey":"01900abc-dead-7000-beef-000000000001",...}
{"event":"upstream.work.called","idempotencyKey":"01900abc-dead-7000-beef-000000000001",...}
{"event":"upstream.work.called","idempotencyKey":"01900abc-dead-7000-beef-000000000001",...}
```

### Second call with same explicit key (dedupe test)
```bash
KEY="01900abc-dead-7000-beef-000000000001"  # use key from previous call

curl -s -X POST http://localhost:8081/admin/reset   # reset to healthy

curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"sku":"X","qty":1}'
# Expected: ACCEPTED, returned from Redis cache — no HTTP call to flaky-upstream
```

Verify no new call reached flaky-upstream:
```bash
docker compose logs flaky-upstream | grep "$KEY" | wc -l
# Expected: still 3 (no new lines added)
```

---

## Quick-start sequence (README reference)

```bash
# 1. Build
./gradlew clean build

# 2. Start stack
docker compose up -d --build

# 3. Wait for healthy
docker compose ps   # all services "running" or "healthy"

# 4. Verify
curl http://localhost:8080/health
curl http://localhost:8081/admin/mode

# 5. Run S1
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":3}'

curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | jq .

# 6. Open observability UIs
open http://localhost:16686   # Jaeger
open http://localhost:3000    # Grafana (admin/admin)
open http://localhost:9090    # Prometheus
```

---

## Acceptance criteria

- All 5 scenarios reproducible with the commands above on a clean Docker Compose stack.
- S1: response `upstreamAttempts == 4`, status `ACCEPTED`.
- S2: after sliding window exhausted, at least one call returns with no upstream HTTP hit (CB fast-fail).
- S3: `time curl` shows ~12–18 s total; response `status: FAILED`, `error_class` contains `Timeout`.
- S4: ≥ 18/20 calls return `ACCEPTED` despite 30% per-attempt failure rate.
- S5: `docker compose logs flaky-upstream | grep idempotencyKey` shows identical key across all retry attempts.
- Each scenario produces at least one visible trace in Jaeger with correct span structure.
- Grafana dashboard shows non-zero values for all 5 panels after running all scenarios.
