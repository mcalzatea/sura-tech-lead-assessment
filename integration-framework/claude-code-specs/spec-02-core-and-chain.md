# Spec 02 — Core Model & Decorator Chain

## Context
This spec implements the central building blocks of the framework:
- Configuration properties POJOs (`@ConfigurationProperties`).
- Domain model: `IntegrationRequest`, `IntegrationResponse`, `IntegrationException`.
- The decorator chain: `TimeLimiter → Retry → CircuitBreaker → HTTP call`.
- The `IntegrationExecutor` class that wires the chain and executes calls via `RestClient`.

No annotation processing or proxy in this spec (that is Spec 03).
No Redis in this spec (that is Spec 04).
No OTel/metrics instrumentation in this spec (that is Spec 05).

**Depends on**: spec-01-scaffold.md (project compiles).

---

## Package structure

```
framework/src/main/java/com/sura/integration/
├── config/
│   ├── IntegrationProperties.java       # @ConfigurationProperties root
│   ├── TargetProperties.java            # per-target config block
│   └── IntegrationAutoConfiguration.java
├── model/
│   ├── IntegrationRequest.java
│   ├── IntegrationResponse.java
│   └── IntegrationException.java
└── core/
    └── IntegrationExecutor.java         # decorator chain + RestClient
```

---

## 1. `IntegrationProperties.java`

`@ConfigurationProperties(prefix = "integration")` bean.

Fields:
- `TargetProperties defaults` — global defaults.
- `Map<String, TargetProperties> targets` — per-target overrides keyed by target name (e.g. `"payment.gateway"`).

Method: `TargetProperties resolve(String targetName)` — merges `targets.get(targetName)` over `defaults`.
Merge rule: any non-null field in the target-specific block overrides the default; null means "inherit default".

## 2. `TargetProperties.java`

Plain POJO with nested static classes. All fields nullable (null = inherit from defaults).

```
TargetProperties
├── TimeoutProps timeout
│   ├── Duration connect          // default 1s
│   └── Duration read             // default 3s
├── RetryProps retry
│   ├── int maxAttempts           // default 3
│   ├── Duration baseBackoff      // default 200ms
│   ├── Duration maxBackoff       // default 2s
│   ├── List<Integer> retryOnStatus   // default [502,503,504]
│   └── List<String> retryOnExceptions // default [TimeoutException, IOException]
├── CircuitBreakerProps circuitBreaker
│   ├── float failureRateThreshold     // default 50.0
│   ├── int slidingWindowSize          // default 10
│   ├── Duration waitDurationInOpenState // default 30s
│   └── int permittedCallsInHalfOpen   // default 3
└── IdempotencyProps idempotency
    ├── Duration ttl              // default 24h
    ├── String headerName         // default "Idempotency-Key"
    └── boolean autoGenerate      // default true
```

## 3. `IntegrationException.java`

Unchecked exception. All internal exceptions must be caught and re-thrown as `IntegrationException`.

Fields:
- `String target` — name of the target that failed.
- `int attemptCount` — number of attempts made before giving up.
- `String traceId` — OTel trace id at the time of failure (populated by Spec 05; use `"unknown"` placeholder here).
- `Throwable cause` — original exception.

Static factory: `IntegrationException.of(String target, int attempts, Throwable cause)`.

## 4. `IntegrationRequest.java`

Immutable value object (use `record` or builder).

Fields:
- `String method` — HTTP method (GET, POST, PUT, PATCH).
- `String path` — relative path (e.g. `/payments`).
- `Object body` — nullable request body.
- `Map<String, String> headers` — additional headers.
- `boolean idempotent` — whether retries are allowed on this request.
- `String idempotencyKey` — caller-supplied key (nullable; framework generates if null + autoGenerate=true).
- `Class<?> responseType` — expected response class for deserialization.

## 5. `IntegrationResponse.java`

Immutable value object (record).

Fields:
- `int statusCode`
- `Object body` — deserialized to `responseType`.
- `Map<String, String> responseHeaders`
- `int attemptCount`
- `long latencyMs`

## 6. `IntegrationExecutor.java`

Central class. One instance per target. Constructed by `IntegrationAutoConfiguration`.

### Constructor dependencies
- `String targetName`
- `String baseUrl`
- `TargetProperties config` — resolved (merged defaults + overrides)
- `RestClient restClient` — pre-configured with `baseUrl`, connect timeout, read timeout
- `RetryRegistry retryRegistry` — Resilience4j
- `CircuitBreakerRegistry circuitBreakerRegistry` — Resilience4j
- `TimeLimiterRegistry timeLimiterRegistry` — Resilience4j

### Method: `<T> IntegrationResponse execute(IntegrationRequest request)`

Decorator chain (outermost → innermost):

```
TimeLimiter.executeCallable(
  Retry.executeCallable(
    CircuitBreaker.executeCallable(
      () -> doHttpCall(request)
    )
  )
)
```

**TimeLimiter**: configured with `read` timeout from `TargetProperties`. Wraps a `Future`/`Callable`.

**Retry**:
- `maxAttempts` from config.
- Exponential backoff with full jitter:
  ```
  delay = random(0, min(maxBackoff, baseBackoff * 2^(attempt-1)))
  ```
- Retry predicate: retries if `IntegrationException` cause is in `retryOnExceptions` OR response status in `retryOnStatus`.
- Only retries if `request.isIdempotent() == true`. If not idempotent, `maxAttempts = 1`.
- On each retry attempt, the same `Idempotency-Key` header value is reused (key set before retry loop starts).

**CircuitBreaker**:
- Sliding window COUNT_BASED, size from config.
- Failure rate threshold from config.
- Transitions: CLOSED → OPEN → HALF_OPEN → CLOSED.
- When OPEN: throws `CallNotPermittedException` wrapped in `IntegrationException`.

**doHttpCall(request)**:
- Uses `RestClient` to execute the HTTP call.
- Sets all headers from `request.headers()`.
- Deserializes response to `request.responseType()`.
- On HTTP 4xx: throws non-retryable `IntegrationException` (no retry).
- On HTTP 5xx matching `retryOnStatus`: throws retryable `IntegrationException`.
- On network/timeout exception: throws retryable `IntegrationException`.
- Returns `IntegrationResponse` on 2xx.

### Exception contract
- ALL exceptions from the chain must be caught and wrapped in `IntegrationException` before propagating to caller.
- No Resilience4j internal exceptions leak outside `IntegrationExecutor`.

---

## 7. `IntegrationAutoConfiguration.java`

`@AutoConfiguration` class registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

Beans to create:
- `IntegrationProperties integrationProperties()` — `@ConfigurationProperties`.
- `RetryRegistry retryRegistry(IntegrationProperties props)` — one `RetryConfig` per target derived from props.
- `CircuitBreakerRegistry circuitBreakerRegistry(IntegrationProperties props)` — one `CircuitBreakerConfig` per target.
- `TimeLimiterRegistry timeLimiterRegistry(IntegrationProperties props)` — one `TimeLimiterConfig` per target.
- `Map<String, IntegrationExecutor> integrationExecutors(...)` — one executor per declared target.

---

## Acceptance criteria

- `TargetProperties.resolve("payment.gateway")` correctly merges override `read: 5s` over default `read: 3s` while inheriting all other defaults.
- `IntegrationExecutor.execute()` on a 503 response retries up to `maxAttempts` times with exponential-jitter delay, then throws `IntegrationException`.
- `IntegrationExecutor.execute()` on a 400 response does NOT retry; throws `IntegrationException` immediately with `attemptCount = 1`.
- `TimeLimiter` fires before `maxAttempts` is exhausted when upstream is slow; exception is `IntegrationException(cause=TimeoutException)`.
- CircuitBreaker opens after `slidingWindowSize` × `failureRateThreshold / 100` failures; subsequent calls throw `IntegrationException` without reaching the HTTP client.
- Non-idempotent requests (`idempotent = false`) are never retried regardless of error type.
- Retries reuse the same `Idempotency-Key` set on the first attempt.
