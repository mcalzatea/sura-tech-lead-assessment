package com.sura.integration.model;

import java.util.Map;

public record IntegrationResponse(
        int statusCode,
        Object body,
        Map<String, String> responseHeaders,
        int attemptCount,
        long latencyMs
) {}
