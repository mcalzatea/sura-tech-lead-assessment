# Integration Framework

A Spring Boot library that provides resilient HTTP client capabilities via a declarative `@IntegrationClient` annotation. Wraps every outbound call in a **TimeLimiter → Retry → CircuitBreaker** decorator chain with server-side idempotency, structured logging, OpenTelemetry tracing, and Micrometer metrics.

---

## Modules

| Module | Description |
|---|---|
| `framework` | Publishable Java library — core decorator chain, annotation proxy, Redis idempotency, observability |
| `demo-service` | Spring Boot app that consumes `framework` and calls `flaky-upstream` |
| `flaky-upstream` | Standalone Spring Boot app simulating an unreliable dependency |

---

## Quick Start

### 1. Prerequisites

- Java 21 (`brew install openjdk@21`)
- Docker + Docker Compose

### 2. Build

```bash
./gradlew clean build
```

### 3. Start the full stack

```bash
docker compose up -d --build
```

Wait ~30 s for all services to reach healthy state:

```bash
docker compose ps
```

### 4. Verify

```bash
curl http://localhost:8080/health              # demo-service liveness
curl http://localhost:8081/actuator/health     # flaky-upstream health
curl http://localhost:8081/admin/mode          # current failure mode (default: healthy)
```

### 5. Send a request

```bash
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | jq .
```

### 6. Open observability UIs

| UI | URL | Credentials |
|---|---|---|
| Jaeger (traces) | http://localhost:16686 | — |
| Grafana (dashboards) | http://localhost:3000 | admin / admin |
| Prometheus (metrics) | http://localhost:9090 | — |

---

## Failure Scenarios

Run individual scenarios against a live stack:

```bash
# Reset between scenarios
./scenarios/reset.sh

# S1 — Transient 503 (Retry + Exponential Backoff + Jitter)
./scenarios/s1-transient-503.sh

# S2 — Permanent 503 (Circuit Breaker opens, then recovers)
./scenarios/s2-circuit-breaker.sh

# S3 — Slow Upstream (TimeLimiter fires, retries exhaust)
./scenarios/s3-timeout.sh

# S4 — Intermittent Errors (Retry absorbs 30% failure rate)
./scenarios/s4-intermittent.sh

# S5 — Idempotency Key Propagation + Redis Cache Deduplication
./scenarios/s5-idempotency.sh

# Run all scenarios in sequence
./scenarios/run-all.sh
```

### S1 — Transient 503

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":3}'

curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | jq .
# Expected: { "status": "ACCEPTED", "upstreamAttempts": 4 }
```

### S2 — Circuit Breaker

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"always_503"}'

for i in $(seq 1 15); do
  curl -s -X POST http://localhost:8080/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}' | jq '{status,upstreamAttempts}'
done
# First calls: upstreamAttempts=3 (retried). Later: upstreamAttempts=0 (CB fast-fail).
```

### S3 — Timeout

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"latency_ms","value":10000}'

time curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}' | jq .
# Expected: ~12–18s total, status: "FAILED"
```

### S4 — Intermittent (30% failure rate)

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"intermittent_p","value":0.3}'

for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"X","qty":1}' | jq -r .status
done
# Expected: ≥18/20 ACCEPTED
```

### S5 — Idempotency

```bash
curl -s -X POST http://localhost:8081/admin/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"transient_5xx","value":2}'

curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"X","qty":1}'

# All 3 attempts carry the same Idempotency-Key:
docker compose logs flaky-upstream | grep idempotencyKey
```

---

## Architecture

```
POST /orders
     │
     ▼
 OrderService
     │  FlakyUpstreamClient.doWork()
     ▼
 @IntegrationClient proxy
     │
     ▼
 IntegrationExecutor
 ┌─────────────────────────────────────────┐
 │  TimeLimiter (read timeout)             │
 │    Retry (exponential jitter backoff)   │
 │      CircuitBreaker (COUNT_BASED)       │
 │        RestClient → flaky-upstream      │
 └─────────────────────────────────────────┘
     │
     ├── OTel span (traceparent propagated)
     ├── Micrometer metrics (calls/retries/cb/duration)
     └── Structured JSON logs (start/retry/success/failure)
```

### Configuration (application.yml)

```yaml
integration:
  defaults:
    timeout:
      connect: 1s
      read: 3s
    retry:
      max-attempts: 3
      base-backoff: 200ms
      max-backoff: 2s
      retry-on-status: [502, 503, 504]
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 10
      wait-duration-in-open-state: 30s
    idempotency:
      ttl: 24h
      auto-generate: true
  targets:
    my-service:
      base-url: http://my-service:8080
      timeout:
        read: 5s
```

### Declaring a client

```java
@IntegrationClient(
    name    = "my-service",
    baseUrl = "${integration.targets.my-service.base-url}",
    config  = "my-service"
)
public interface MyServiceClient {

    @PostExchange("/process")
    @Idempotent
    ProcessResponse process(@RequestBody ProcessRequest request);
}
```

Enable scanning in your `@SpringBootApplication`:

```java
@SpringBootApplication
@EnableIntegrationClients(basePackages = "com.example.client")
public class MyApp { ... }
```

---

## Metrics reference

| Metric | Type | Tags |
|---|---|---|
| `integration_client_calls_total` | Counter | `target`, `outcome` (success/error/cb_open) |
| `integration_client_retries_total` | Counter | `target` |
| `integration_client_cb_state` | Gauge | `target`, `state` (CLOSED/OPEN/HALF_OPEN) |
| `integration_client_duration_seconds` | Timer | `target`, `outcome` |

Scraped by Prometheus at `/actuator/prometheus`. Dashboards auto-provisioned in Grafana.
