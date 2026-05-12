# Spec 04 — Idempotency Key Store (Redis)

## Context
Implements server-side idempotency key storage and deduplication using Redis (Lettuce via
Spring Data Redis). Used by `IntegrationClientInterceptor` (Spec 03) to cache responses and
skip duplicate HTTP calls across retries and independent invocations.

**Depends on**: spec-02-core-and-chain.md (`IntegrationResponse` model exists).

---

## Package structure

```
framework/src/main/java/com/sura/integration/
└── idempotency/
    ├── IdempotencyStore.java          # interface
    ├── RedisIdempotencyStore.java     # Redis implementation
    ├── IdempotencyKeyGenerator.java   # UUID v7 generation
    └── CachedResponse.java            # Redis-serializable value object
```

---

## 1. `IdempotencyStore.java` (interface)

```java
public interface IdempotencyStore {

    /**
     * Returns a cached response for the key, or empty if not found.
     */
    Optional<CachedResponse> get(String key);

    /**
     * Stores the response under the key with the given TTL.
     * No-op if the key already exists (first-write-wins semantics).
     */
    void store(String key, IntegrationResponse response, Duration ttl);

    /**
     * Generates a new unique idempotency key.
     */
    String generateKey();
}
```

## 2. `CachedResponse.java`

Redis-serializable record (must be Jackson-serializable):

```java
public record CachedResponse(
    int statusCode,
    String bodyJson,          // raw JSON string of the body
    Map<String, String> responseHeaders,
    Instant storedAt
) {}
```

Serialization note: store the body as a raw JSON string (not Object) to avoid type-erasure
issues on deserialization. The interceptor deserializes `bodyJson` to the method return type
using `ObjectMapper` when returning from cache.

## 3. `RedisIdempotencyStore.java`

Implements `IdempotencyStore`. Spring `@Component`.

### Dependencies
- `StringRedisTemplate redisTemplate` — Spring Data Redis.
- `ObjectMapper objectMapper` — Jackson.
- `IdempotencyKeyGenerator keyGenerator`.

### Redis key format
```
integration:idempotency:{key}
```
Example: `integration:idempotency:01900abc-dead-7000-beef-000000000001`

### `get(String key)`
1. `redisTemplate.opsForValue().get("integration:idempotency:" + key)`.
2. If null: return `Optional.empty()`.
3. Deserialize JSON string → `CachedResponse` via `objectMapper`.
4. Return `Optional.of(cachedResponse)`.

### `store(String key, IntegrationResponse response, Duration ttl)`
1. Serialize `response.body()` to JSON string via `objectMapper`.
2. Build `CachedResponse(statusCode, bodyJson, responseHeaders, Instant.now())`.
3. Serialize `CachedResponse` to JSON string.
4. Use `redisTemplate.opsForValue().setIfAbsent(redisKey, json, ttl)` — **first-write-wins**.
   - `setIfAbsent` maps to Redis `SET NX PX` — atomic, no race condition.

### `generateKey()`
Delegates to `IdempotencyKeyGenerator.generate()`.

## 4. `IdempotencyKeyGenerator.java`

```java
@Component
public class IdempotencyKeyGenerator {

    /**
     * Generates a UUID version 7 (time-ordered, monotonic).
     * Uses com.fasterxml.uuid:java-uuid-generator library.
     */
    public String generate() {
        return Generators.timeBasedEpochGenerator().generate().toString();
    }
}
```

Rationale for UUID v7: time-ordered keys improve Redis memory locality and make logs
easier to correlate by time.

## 5. Redis configuration

`IntegrationAutoConfiguration` (Spec 02) registers:

```java
@Bean
@ConditionalOnClass(StringRedisTemplate.class)
public IdempotencyStore idempotencyStore(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        IdempotencyKeyGenerator keyGenerator) {
    return new RedisIdempotencyStore(redisTemplate, objectMapper, keyGenerator);
}
```

`@ConditionalOnClass` ensures graceful degradation: if Redis is not on the classpath or
`StringRedisTemplate` is not available, no bean is registered and the interceptor skips
idempotency caching (still executes the HTTP call, still propagates the key header).

## 6. Redis serialization config

Register a `RedisSerializer` for JSON in autoconfiguration:

```java
@Bean
public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
}
```

All values stored as plain JSON strings (no Java serialization).

---

## Acceptance criteria

- `store(key, response, ttl)` followed by `get(key)` returns the original response body deserialized to the correct type.
- Two concurrent `store()` calls with the same key: only the first write persists (Redis `SET NX` atomicity).
- `get()` on an expired key returns `Optional.empty()`.
- `generateKey()` returns a valid UUID v7 string (`[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`).
- Successive `generateKey()` calls return lexicographically increasing values (time-ordered property).
- When `StringRedisTemplate` bean is absent, `IdempotencyStore` bean is not registered and the application context still starts.
- Integration test using Testcontainers Redis: store → TTL expiry → get returns empty.
