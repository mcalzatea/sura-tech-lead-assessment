# Spec 06 — Tests

## Context
Unit and integration tests covering all acceptance criteria from Specs 02–05.
Uses WireMock for HTTP stubbing and Testcontainers for Redis.
No Spring Boot test slice needed for unit tests; use `@SpringBootTest` only for integration tests.

**Depends on**: spec-02 through spec-05 (all framework classes exist).

---

## Package structure

```
framework/src/test/java/com/sura/integration/
├── unit/
│   ├── RetryDecoratorTest.java
│   ├── CircuitBreakerDecoratorTest.java
│   ├── TimeLimiterDecoratorTest.java
│   ├── IdempotencyKeyGeneratorTest.java
│   ├── IntegrationLoggerRedactionTest.java
│   └── TargetPropertiesMergeTest.java
└── integration/
    ├── IntegrationExecutorIT.java        # WireMock + full chain
    └── RedisIdempotencyStoreIT.java      # Testcontainers Redis
```

---

## Unit Tests

### `RetryDecoratorTest`

Test cases (use `IntegrationExecutor` directly with mocked HTTP call via lambda):

1. **`retries_on_503_up_to_maxAttempts`**
   - Stub: first 2 calls → 503, 3rd call → 200.
   - Assert: `response.statusCode() == 200`, `response.attemptCount() == 3`.

2. **`does_not_retry_on_400`**
   - Stub: returns 400.
   - Assert: `IntegrationException` thrown, `attemptCount == 1`.

3. **`does_not_retry_non_idempotent_request`**
   - Request with `idempotent = false`.
   - Stub: returns 503.
   - Assert: `IntegrationException` thrown after exactly 1 attempt.

4. **`reuses_idempotency_key_across_retries`**
   - Capture `Idempotency-Key` header value on each attempt.
   - Assert: all captured values are equal to the key set on the first attempt.

5. **`exponential_backoff_delays_are_within_bounds`**
   - Record actual sleep durations across 3 retries.
   - Assert: each delay `d_n` satisfies `0 <= d_n <= min(maxBackoff, baseBackoff * 2^n)`.

### `CircuitBreakerDecoratorTest`

1. **`opens_after_failure_threshold`**
   - Configure: `slidingWindowSize = 5`, `failureRateThreshold = 60` (3/5 failures → OPEN).
   - Stub: 3 failures then 2 successes.
   - Assert: after the 5th call, CB state is OPEN.

2. **`fast_fails_when_open`**
   - Pre-open the CB manually.
   - Call `executor.execute(...)`.
   - Assert: `IntegrationException` thrown without the HTTP stub being invoked.

3. **`transitions_to_half_open_after_wait`**
   - Open the CB. Advance clock past `waitDurationInOpenState`.
   - Assert: CB state is HALF_OPEN.

### `TimeLimiterDecoratorTest`

1. **`fires_timeout_and_wraps_in_integration_exception`**
   - Stub: sleeps 5s. TimeLimiter config: `read = 500ms`.
   - Assert: `IntegrationException` thrown with `cause instanceof TimeoutException`.
   - Assert: total elapsed time < 1s.

2. **`timeout_triggers_retry`**
   - TimeLimiter config: `read = 200ms`. Retry config: `maxAttempts = 3`.
   - Stub: first 2 calls sleep 1s (timeout), 3rd call returns 200 immediately.
   - Assert: response 200, `attemptCount == 3`.

### `IdempotencyKeyGeneratorTest`

1. **`generates_valid_uuid_v7`**
   - Call `generateKey()`.
   - Assert: matches UUID format with version digit = `7`.

2. **`successive_keys_are_time_ordered`**
   - Generate 100 keys in a loop.
   - Assert: each key is lexicographically >= previous key.

### `IntegrationLoggerRedactionTest`

1. **`redacts_configured_fields_in_body`**
   - Config: `redact = [password, card_number]`.
   - Input body JSON: `{"user": "alice", "password": "secret123", "card_number": "4111111111111111"}`.
   - Assert: log output contains `"password": "[REDACTED]"` and `"card_number": "[REDACTED]"`.
   - Assert: `"user": "alice"` is NOT redacted.

2. **`authorization_header_always_redacted`**
   - Log a request with `Authorization: Bearer token123`.
   - Assert: `Bearer token123` does not appear in log output regardless of redact config.

3. **`body_not_logged_when_log_bodies_false`**
   - Config: `log-bodies = false`.
   - Assert: no body content in log output.

### `TargetPropertiesMergeTest`

1. **`target_override_takes_precedence_over_default`**
   - Default: `read = 3s`. Target `payment.gateway`: `read = 5s`.
   - Assert: `resolve("payment.gateway").timeout().read() == Duration.ofSeconds(5)`.

2. **`null_override_field_inherits_default`**
   - Target `payment.gateway` has no `circuitBreaker` block.
   - Assert: `resolve("payment.gateway").circuitBreaker().failureRateThreshold() == 50.0f`.

---

## Integration Tests

### `IntegrationExecutorIT` (WireMock)

Setup:
```java
@RegisterExtension
static WireMockExtension wiremock = WireMockExtension.newInstance()
    .options(wireMockConfig().dynamicPort())
    .build();
```

Spring context loaded with `@SpringBootTest` pointing `integration.targets.test.baseUrl` to
`wiremock.baseUrl()`.

Test cases:

1. **`full_chain_success_on_third_attempt`**
   - Stub: POST `/work` → 503 twice, then 200.
   - Assert: response 200, `attemptCount == 3`.
   - Assert: `integration.client.retries` counter = 2.
   - Assert: OTel span `HTTP POST test/work` recorded with `http.response.status_code = 200`.

2. **`circuit_breaker_opens_and_fast_fails`**
   - Stub: POST `/work` → 503 always.
   - Make `slidingWindowSize` calls.
   - Assert: subsequent call throws `IntegrationException` with no WireMock request recorded.
   - Assert: `integration.client.cb.state{state=OPEN}` gauge = 1.

3. **`idempotency_key_reused_across_retries`**
   - Stub: captures all received `Idempotency-Key` header values; 503 twice then 200.
   - Assert: all captured header values are identical.

4. **`traceparent_header_propagated`**
   - Stub: captures all request headers.
   - Assert: `traceparent` header present and matches W3C format
     `00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}`.

5. **`non_retryable_4xx_fails_immediately`**
   - Stub: POST `/work` → 404.
   - Assert: `IntegrationException` thrown, `attemptCount == 1`, WireMock received exactly 1 request.

### `RedisIdempotencyStoreIT` (Testcontainers)

Setup:
```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7")
    .withExposedPorts(6379);
```

Test cases:

1. **`store_and_get_returns_cached_response`**
   - Store a response with key `k1`, TTL 1 minute.
   - Get with `k1`.
   - Assert: returned body matches original.

2. **`get_returns_empty_after_ttl_expiry`**
   - Store with TTL 1 second.
   - Sleep 2 seconds.
   - Assert: `get(k1)` returns `Optional.empty()`.

3. **`concurrent_store_is_idempotent`**
   - Store same key from 2 threads simultaneously.
   - Assert: only one value persists (first-write-wins).

4. **`get_on_unknown_key_returns_empty`**
   - `get("nonexistent-key")`.
   - Assert: `Optional.empty()`.

---

## Test dependencies (already in `framework/build.gradle.kts` from Spec 01)

```
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("com.github.tomakehurst:wiremock-standalone:3.5.4")
testImplementation("org.testcontainers:testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
```

---

## Acceptance criteria

- `./gradlew :framework:test` passes with zero failures.
- No test uses `Thread.sleep` for more than 3 seconds (use Resilience4j clock mocks where possible).
- No test depends on external network (WireMock and Testcontainers only).
- Coverage: all classes in `core/`, `idempotency/`, `observability/` have at least one direct test.
- Each test class has a clear `// given / when / then` comment structure.
