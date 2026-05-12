# Spec 05 — Observability (OpenTelemetry · Micrometer · Prometheus · Structured Logging)

## Context
Adds full observability to `IntegrationExecutor` and the proxy layer:
- OTel spans per outbound call with W3C `traceparent` propagation.
- Micrometer metrics exposed via Prometheus scrape endpoint.
- Structured JSON log lines on entry, retry, success, and failure with PII redaction.

**Depends on**: spec-02-core-and-chain.md (`IntegrationExecutor` exists and must be modified here).

---

## Package structure

```
framework/src/main/java/com/sura/integration/
└── observability/
    ├── IntegrationSpanDecorator.java    # OTel span lifecycle
    ├── IntegrationMetrics.java          # Micrometer counters/histograms/gauges
    └── IntegrationLogger.java           # Structured JSON logging + PII redaction
```

---

## 1. OpenTelemetry — `IntegrationSpanDecorator.java`

### Span lifecycle

Open a child span wrapping the full decorator chain (outside `TimeLimiter`):

```
Span span = tracer.spanBuilder("HTTP " + request.method() + " " + targetName + request.path())
    .setSpanKind(SpanKind.CLIENT)
    .startSpan();
```

Span attributes (OpenTelemetry semantic conventions):

| Attribute | Value |
|---|---|
| `http.request.method` | `request.method()` |
| `url.full` | `baseUrl + request.path()` |
| `server.address` | target host |
| `peer.service` | `targetName` |
| `http.response.status_code` | response status (set on success) |
| `integration.attempt_count` | final attempt count |
| `integration.idempotency_key` | key value if present (never redact this; it is not PII) |

### Context propagation

Before each HTTP call (inside `doHttpCall`), inject W3C `traceparent` into outbound headers:

```java
W3CTraceContextPropagator.getInstance()
    .inject(Context.current(), headers, (map, key, value) -> map.put(key, value));
```

This ensures the upstream receives the `traceparent` header and the trace continues end-to-end.

### Span error handling

On `IntegrationException`:
```java
span.setStatus(StatusCode.ERROR, exception.getMessage());
span.recordException(exception.getCause());
```

Always call `span.end()` in a `finally` block.

### Populate `traceId` in `IntegrationException`

After span is created, read `Span.current().getSpanContext().getTraceId()` and pass it into
`IntegrationException.of(target, attempts, traceId, cause)`.
Update `IntegrationException` constructor in Spec 02 to accept `traceId` string.

### Bean registration

```java
@Bean
public Tracer integrationTracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("com.sura.integration", "1.0.0");
}
```

---

## 2. Micrometer Metrics — `IntegrationMetrics.java`

Spring `@Component`. Injected into `IntegrationExecutor`.

### Metrics to register

| Metric name | Type | Tags | Description |
|---|---|---|---|
| `integration.client.calls` | Counter | `target`, `outcome` (success/error/cb_open) | Total calls attempted |
| `integration.client.retries` | Counter | `target` | Incremented on each retry attempt (not first attempt) |
| `integration.client.cb.state` | Gauge | `target`, `state` (CLOSED/OPEN/HALF_OPEN) | Current CB state (0/1 per state) |
| `integration.client.duration` | Timer/Histogram | `target`, `outcome` | End-to-end call duration including retries |

### Implementation notes

- `integration.client.calls` and `integration.client.duration`: record after the full chain completes.
- `integration.client.retries`: increment inside the Retry event listener:
  ```java
  retryRegistry.retry(targetName).getEventPublisher()
      .onRetry(e -> metrics.incrementRetries(targetName));
  ```
- `integration.client.cb.state`: register as a `MultiGauge` or three gauges (one per state).
  Attach to CircuitBreaker event publisher:
  ```java
  cb.getEventPublisher().onStateTransition(e -> metrics.updateCbState(targetName, e.getStateTransition().getToState()));
  ```
- All metrics must be tagged with `target` = `targetName` for per-upstream dashboards.

### Prometheus endpoint

`demo-service` exposes `/actuator/prometheus` via Spring Boot Actuator + `micrometer-registry-prometheus`.
No additional config needed in framework; the library registers metrics to the global `MeterRegistry`.

---

## 3. Structured Logging — `IntegrationLogger.java`

Spring `@Component`. Uses `org.slf4j.Logger` with `logstash-logback-encoder` for JSON output.

### Log events

#### On call entry (before chain execution)
```json
{
  "event": "integration.call.start",
  "target": "payment-gateway",
  "method": "POST",
  "path": "/payments",
  "idempotency_key": "01900abc-...",
  "trace_id": "...",
  "span_id": "..."
}
```

#### On retry attempt
```json
{
  "event": "integration.call.retry",
  "target": "payment-gateway",
  "attempt": 2,
  "delay_ms": 347,
  "error_class": "java.io.IOException",
  "trace_id": "...",
  "span_id": "..."
}
```

#### On success
```json
{
  "event": "integration.call.success",
  "target": "payment-gateway",
  "status_code": 200,
  "attempt_count": 2,
  "latency_ms": 423,
  "trace_id": "...",
  "span_id": "..."
}
```

#### On failure (after exhausting retries or non-retryable error)
```json
{
  "event": "integration.call.failure",
  "target": "payment-gateway",
  "attempt_count": 3,
  "error_class": "java.net.SocketTimeoutException",
  "latency_ms": 9100,
  "trace_id": "...",
  "span_id": "..."
}
```

### PII Redaction

`IntegrationLogger` reads `LoggingProps.redact` list from config (e.g. `[password, ssn, card_number, authorization]`).

Redaction logic:
- Applied to request/response body strings before logging (only when `log-bodies: true`).
- Pattern: for each field name in the redact list, replace the value in the JSON string:
  ```java
  // regex per field: "fieldName"\s*:\s*"[^"]*"  →  "fieldName": "[REDACTED]"
  ```
- Never log body by default (`log-bodies: false`). When enabled, always apply redaction first.
- Headers in `authorization` field are always redacted regardless of `log-bodies` setting.

### trace_id / span_id injection

Read from OTel context at log time:
```java
SpanContext ctx = Span.current().getSpanContext();
String traceId = ctx.isValid() ? ctx.getTraceId() : "none";
String spanId  = ctx.isValid() ? ctx.getSpanId()  : "none";
```

These are added as structured fields (not in the message string) via `StructuredArguments.kv(...)` from `logstash-logback-encoder`.

---

## 4. Wiring into `IntegrationExecutor`

Modify `IntegrationExecutor` (Spec 02) to accept and call these three components:

```java
// constructor additions
private final IntegrationSpanDecorator spanDecorator;
private final IntegrationMetrics metrics;
private final IntegrationLogger logger;
```

Execution flow with observability:
```
span = spanDecorator.startSpan(request)
logger.logStart(request)
try {
  response = TimeLimiter → Retry [logger.logRetry on each retry] → CB → doHttpCall
  spanDecorator.onSuccess(span, response)
  logger.logSuccess(response)
  metrics.recordCall(targetName, "success", latency)
  return response
} catch (IntegrationException e) {
  spanDecorator.onError(span, e)
  logger.logFailure(e)
  metrics.recordCall(targetName, outcomeFromException(e), latency)
  throw e
} finally {
  spanDecorator.endSpan(span)
}
```

---

## 5. Grafana dashboard (provisioning file)

File: `grafana-provisioning/dashboards/integration-framework.json`

Panels to include:
1. **Call rate** — `rate(integration_client_calls_total[1m])` grouped by `target`, `outcome`.
2. **Error rate** — `rate(integration_client_calls_total{outcome="error"}[1m]) / rate(integration_client_calls_total[1m])`.
3. **Retry rate** — `rate(integration_client_retries_total[1m])` by `target`.
4. **Circuit breaker state** — `integration_client_cb_state` by `target`, `state`.
5. **P50/P95/P99 latency** — histogram quantiles from `integration_client_duration_seconds`.

Provision via `grafana-provisioning/datasources/prometheus.yaml` pointing to `http://prometheus:9090`.

---

## Acceptance criteria

- Every outbound call produces a child OTel span; span name matches `HTTP POST flaky-upstream/work`.
- Span carries `http.response.status_code`, `peer.service`, `integration.attempt_count`.
- `traceparent` header is present on every outbound HTTP request.
- On failure, span status is ERROR and `exception.message` is recorded.
- `integration.client.calls` counter increments on every call with correct `outcome` tag.
- `integration.client.retries` counter increments once per retry (not per first attempt).
- `integration.client.cb.state` gauge reflects OPEN when circuit breaker trips.
- `integration.client.duration` histogram records total latency including all retry delays.
- Log lines for start, retry, success, failure are emitted as valid JSON with `trace_id` and `span_id`.
- Body logging disabled by default; when enabled, fields in redact list are replaced with `[REDACTED]`.
- `authorization` header value never appears in logs.
- `/actuator/prometheus` exposes all four metric families.
