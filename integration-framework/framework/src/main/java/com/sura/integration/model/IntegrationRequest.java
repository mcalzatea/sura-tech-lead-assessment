package com.sura.integration.model;

import java.util.Map;

public record IntegrationRequest(
        String method,
        String path,
        Object body,
        Map<String, String> headers,
        boolean idempotent,
        String idempotencyKey,
        Class<?> responseType
) {
    public IntegrationRequest {
        headers = (headers != null) ? Map.copyOf(headers) : Map.of();
    }
}
