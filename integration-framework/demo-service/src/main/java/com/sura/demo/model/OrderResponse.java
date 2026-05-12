package com.sura.demo.model;

public record OrderResponse(
        String orderId,
        String status,
        int upstreamAttempts,
        long latencyMs,
        String traceId
) {}
