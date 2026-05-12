# Spec 07 — Flaky Upstream Service

## Context
Standalone Spring Boot application. Simulates an unreliable dependency with controllable
failure modes. No resilience logic — plain HTTP server only. Used exclusively by `demo-service`
in the Docker Compose network.

**Depends on**: spec-01-scaffold.md (`flaky-upstream` module exists in Gradle).
**No dependency on `framework` module.**

---

## Package structure

```
flaky-upstream/src/main/java/com/sura/flaky/
├── FlakyUpstreamApplication.java
├── mode/
│   ├── FailureModeType.java      # enum
│   ├── FailureModeConfig.java    # record: current mode + params
│   └── FailureModeService.java   # thread-safe mode management + evaluate()
└── controller/
    ├── WorkController.java       # POST /work
    └── AdminController.java      # POST/GET /admin/mode, POST /admin/reset
```

---

## 1. `FailureModeType.java`

```java
public enum FailureModeType {
    HEALTHY,
    TRANSIENT_5XX,    // fails first N requests, then recovers
    ALWAYS_503,
    LATENCY_MS,       // sleeps N ms before responding 200
    INTERMITTENT_P    // returns 503 with probability X (0.0–1.0)
}
```

## 2. `FailureModeConfig.java`

```java
public record FailureModeConfig(
    FailureModeType type,
    int intValue,          // N for TRANSIENT_5XX and LATENCY_MS
    double doubleValue,    // X for INTERMITTENT_P
    AtomicInteger callCount // internal counter for TRANSIENT_5XX; reset on mode change
) {
    public static FailureModeConfig healthy() {
        return new FailureModeConfig(FailureModeType.HEALTHY, 0, 0.0, new AtomicInteger(0));
    }
}
```

## 3. `FailureModeService.java`

Spring `@Service`. Thread-safe via `AtomicReference<FailureModeConfig>`.

```java
private final AtomicReference<FailureModeConfig> current =
    new AtomicReference<>(FailureModeConfig.healthy());
```

### Methods

- `void setMode(FailureModeConfig config)` — atomically replaces current config.
- `FailureModeConfig getMode()` — returns current config snapshot.
- `void reset()` — sets back to `FailureModeConfig.healthy()`.
- `WorkResult evaluate()` — core decision logic:

```
switch current.type():

  HEALTHY:
    sleep random(50, 100) ms
    return WorkResult.ok()

  TRANSIENT_5XX:
    n = callCount.incrementAndGet()
    if n <= intValue → return WorkResult.fail(503)
    else             → sleep random(50, 100) ms; return WorkResult.ok()

  ALWAYS_503:
    return WorkResult.fail(503)

  LATENCY_MS:
    sleep intValue ms
    return WorkResult.ok()

  INTERMITTENT_P:
    if ThreadLocalRandom.current().nextDouble() < doubleValue → return WorkResult.fail(503)
    else → sleep random(50, 100) ms; return WorkResult.ok()
```

### `WorkResult` record

```java
public record WorkResult(boolean success, int statusCode, String message) {
    public static WorkResult ok()          { return new WorkResult(true,  200, "OK"); }
    public static WorkResult fail(int s)   { return new WorkResult(false, s,   "Simulated failure"); }
}
```

---

## 4. `WorkController.java`

```
POST /work
Content-Type: application/json
Body: any (ignored by flaky-upstream; passed through for idempotency key logging)
```

Logic:
1. Read `Idempotency-Key` header if present — log it as structured field.
2. Call `failureModeService.evaluate()`.
3. If `result.success()`: return HTTP 200 `{"status":"ok","requestId":"<uuid>"}`.
4. If `!result.success()`: return HTTP `result.statusCode()` `{"status":"error","message":"Simulated failure"}`.

Structured log per call:
```json
{
  "event": "upstream.work.called",
  "mode": "transient_5xx",
  "statusCode": 503,
  "idempotencyKey": "01900abc-...",
  "requestId": "uuid"
}
```

---

## 5. `AdminController.java`

### `POST /admin/mode`

Request body (Jackson-deserialized):
```json
{ "mode": "transient_5xx", "value": 3 }
{ "mode": "always_503" }
{ "mode": "latency_ms", "value": 10000 }
{ "mode": "intermittent_p", "value": 0.3 }
{ "mode": "healthy" }
```

Mapping rules:
- `mode` field → `FailureModeType` (case-insensitive, replace `-` with `_`).
- `value` → `intValue` for TRANSIENT_5XX / LATENCY_MS; `doubleValue` for INTERMITTENT_P.
- Calls `failureModeService.setMode(new FailureModeConfig(...))`.
- Returns HTTP 200 with current mode as JSON.

### `GET /admin/mode`

Returns:
```json
{ "mode": "transient_5xx", "intValue": 3, "callCount": 1 }
```

### `POST /admin/reset`

Calls `failureModeService.reset()`. Returns HTTP 200 `{"mode":"healthy"}`.

---

## 6. `application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: flaky-upstream

logging:
  structured: true   # uses logback-spring.xml from classpath if present

management:
  endpoints:
    web:
      exposure:
        include: health
```

---

## Acceptance criteria

- `POST /admin/mode {"mode":"transient_5xx","value":3}` + 4 calls to `POST /work`:
  calls 1–3 return 503, call 4 returns 200.
- `POST /admin/mode {"mode":"always_503"}`: all subsequent `/work` return 503.
- `POST /admin/mode {"mode":"latency_ms","value":10000}`: `/work` responds after ~10 s with 200.
- `POST /admin/mode {"mode":"intermittent_p","value":0.3}`: over 100 calls, 15–45% fail (probabilistic).
- `POST /admin/reset`: next `/work` returns 200 within 150 ms.
- Mode state persists across calls until explicitly changed or reset.
- Concurrent calls to `/work` have no race condition on `callCount` (use `AtomicInteger`).
- `Idempotency-Key` header value appears in structured log for every `/work` call that carries it.
