package com.sura.integration.observability;

import com.sura.integration.model.IntegrationException;
import com.sura.integration.model.IntegrationRequest;
import com.sura.integration.model.IntegrationResponse;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.net.URI;
import java.util.Map;

public class IntegrationSpanDecorator {

    private final Tracer tracer;
    private final OpenTelemetry openTelemetry;

    public IntegrationSpanDecorator(Tracer tracer, OpenTelemetry openTelemetry) {
        this.tracer        = tracer;
        this.openTelemetry = openTelemetry;
    }

    public Span startSpan(IntegrationRequest request, String targetName, String baseUrl) {
        Span span = tracer.spanBuilder("HTTP " + request.method() + " " + targetName + request.path())
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("http.request.method", request.method());
        span.setAttribute("url.full", safeUrl(baseUrl, request.path()));
        span.setAttribute("peer.service", targetName);
        span.setAttribute("server.address", extractHost(baseUrl));

        if (request.idempotencyKey() != null) {
            span.setAttribute("integration.idempotency_key", request.idempotencyKey());
        }
        return span;
    }

    public void onSuccess(Span span, IntegrationResponse response) {
        span.setAttribute("http.response.status_code", response.statusCode());
        span.setAttribute("integration.attempt_count", response.attemptCount());
        span.setStatus(StatusCode.OK);
    }

    public void onError(Span span, IntegrationException e) {
        span.setAttribute("integration.attempt_count", e.getAttemptCount());
        span.setStatus(StatusCode.ERROR, e.getMessage());
        if (e.getCause() != null) {
            span.recordException(e.getCause());
        }
    }

    public void endSpan(Span span) {
        span.end();
    }

    public void injectContext(Map<String, String> headers) {
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, (map, key, value) -> map.put(key, value));
    }

    private String safeUrl(String baseUrl, String path) {
        if (baseUrl == null) return path;
        return baseUrl.endsWith("/") ? baseUrl + path.replaceFirst("^/", "") : baseUrl + path;
    }

    private String extractHost(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (Exception e) {
            return baseUrl;
        }
    }
}
