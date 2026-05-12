package com.sura.integration.idempotency;

import java.time.Instant;
import java.util.Map;

/**
 * Redis-serializable representation of a stored integration response.
 * Body is kept as raw JSON to avoid type-erasure issues on deserialization.
 */
public record CachedResponse(
        int statusCode,
        String bodyJson,
        Map<String, String> responseHeaders,
        Instant storedAt
) {}
