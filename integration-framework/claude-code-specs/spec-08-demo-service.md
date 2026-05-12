# Spec 08 — Demo Service

## Context
Spring Boot application that consumes the `framework` module (Section B) to call
`flaky-upstream` via the declarative `@IntegrationClient` API. Exposes `POST /orders`
as the single business endpoint that exercises the full decorator chain.

**Depends on**: spec-02 through spec-05 (framework module complete).
**Depends on**: spec-07 (`flaky-upstream` running — for integration behavior).

---

## Package structure

```
demo-service/src/main/java/com/sura/demo/
├── DemoServiceApplication.java
├── client/
│   └── FlakyUpstreamClient.java      # @IntegrationClient interface
├── controller/
│   └── OrderController.java          # POST /orders
├── service/
│   └── OrderService.java             # business logic, calls FlakyUpstreamClient
└── model/
    ├── OrderRequest.java             # inbound request body
    ├── OrderResponse.java            # response to caller
    ├── WorkRequest.java              # outbound body to flaky-upstream
    └── WorkResponse.java             # response from flaky-upstream
```

---

## 1. `FlakyUpstreamClient.java`

```java
@IntegrationClient(
    name        = "flaky-upstream",
    baseUrl     = "${integration.targets.flaky-upstream.base-url}",
    config      = "flaky-upstream"
)
public interface FlakyUpstreamClient {

    @PostExchange("/work")
    @Idempotent
    WorkResponse doWork(@RequestBody WorkRequest request);
}
```

Notes:
- `baseUrl` is externalized via `application.yml` so it resolves to `http://flaky-upstream:8081`
  in Docker Compose and `http://localhost:8081` locally.
- `@Idempotent` enables retry + idempotency key generation/propagation.

---

## 2. `OrderRequest.java` / `OrderResponse.java`

```java
public record OrderRequest(String sku, int qty) {}

public record OrderResponse(
    String orderId,
    String status,        // "ACCEPTED" | "FAILED"
    int upstreamAttempts,
    long latencyMs,
    String traceId
) {}
```

## 3. `WorkRequest.java` / `WorkResponse.java`

```java
public record WorkRequest(String sku, int qty, String correlationId) {}

public record WorkResponse(String status, String requestId) {}
```

---

## 4. `OrderService.java`

Spring `@Service`.

```java
public OrderResponse process(OrderRequest req) {
    String correlationId = generateCorrelationId();  // simple UUID
    long start = System.currentTimeMillis();

    try {
        WorkResponse work = flakyUpstreamClient.doWork(
            new WorkRequest(req.sku(), req.qty(), correlationId)
        );
        return new OrderResponse(
            UUID.randomUUID().toString(),
            "ACCEPTED",
            /* attemptCount from IntegrationResponse — expose via MDC or response */,
            System.currentTimeMillis() - start,
            currentTraceId()
        );
    } catch (IntegrationException e) {
        return new OrderResponse(
            null,
            "FAILED",
            e.getAttemptCount(),
            System.currentTimeMillis() - start,
            e.getTraceId()
        );
    }
}
```

`currentTraceId()` helper:
```java
private String currentTraceId() {
    SpanContext ctx = Span.current().getSpanContext();
    return ctx.isValid() ? ctx.getTraceId() : "none";
}
```

---

## 5. `OrderController.java`

```
POST /orders
Content-Type: application/json
Body: { "sku": "X", "qty": 1 }
```

Logic:
- If `orderResponse.status() == "ACCEPTED"`: return HTTP 200 with `OrderResponse` body.
- If `orderResponse.status() == "FAILED"`: return HTTP 502 with `OrderResponse` body.

```java
@PostMapping("/orders")
public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
    OrderResponse response = orderService.process(request);
    int status = "ACCEPTED".equals(response.status()) ? 200 : 502;
    return ResponseEntity.status(status).body(response);
}
```

Additional endpoints:
```
GET /health          → Spring Boot Actuator liveness
GET /actuator/prometheus → Micrometer Prometheus scrape
```

---

## 6. `application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: demo-service
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379

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
      permitted-calls-in-half-open: 3
    idempotency:
      ttl: 24h
      header-name: Idempotency-Key
      auto-generate: true
    logging:
      redact: [password, ssn, card_number, authorization]
      log-bodies: false
  targets:
    flaky-upstream:
      base-url: ${FLAKY_UPSTREAM_URL:http://localhost:8081}
      timeout:
        read: 4s   # slightly above flaky default healthy latency

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    export:
      prometheus:
        enabled: true

# OTel exporter — OTLP to collector
otel:
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}
  service:
    name: demo-service
```

---

## 7. `DemoServiceApplication.java`

```java
@SpringBootApplication
@EnableIntegrationClients(basePackages = "com.sura.demo.client")
public class DemoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}
```

---

## Acceptance criteria

- `POST /orders {"sku":"X","qty":1}` with `flaky-upstream` in `healthy` mode returns HTTP 200
  with `status: "ACCEPTED"`.
- With `flaky-upstream` in `transient_5xx=2`, same call returns HTTP 200 after retries
  (`upstreamAttempts == 3`).
- With `flaky-upstream` in `always_503`, after CB opens, returns HTTP 502 with
  `status: "FAILED"` and no WireMock/upstream call made.
- Response body always contains `traceId` matching the OTel trace visible in Jaeger.
- `/actuator/prometheus` exposes `integration_client_calls_total`,
  `integration_client_retries_total`, `integration_client_duration_seconds`.
- Application starts and accepts requests within 15 s of `docker compose up`.
