package com.sura.integration.idempotency;

import com.sura.integration.model.IntegrationResponse;

import java.time.Duration;
import java.util.Optional;

/**
 * Store for server-side idempotency deduplication.
 * {@code RedisIdempotencyStore} is the production implementation (Spec-04).
 */
public interface IdempotencyStore {

    /**
     * Returns the cached response for the key, or empty if not found or expired.
     */
    Optional<CachedResponse> get(String key);

    /**
     * Stores the response under the key with the given TTL.
     * No-op if the key already exists (first-write-wins semantics via Redis SET NX).
     */
    void store(String key, IntegrationResponse response, Duration ttl);

    /**
     * Generates a new unique idempotency key (UUID v7).
     */
    String generateKey();
}
