package com.sura.integration.observability;

import com.sura.integration.config.TargetProperties;
import com.sura.integration.model.IntegrationException;
import com.sura.integration.model.IntegrationRequest;
import com.sura.integration.model.IntegrationResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

public class IntegrationLogger {

    private static final Logger log = LoggerFactory.getLogger(IntegrationLogger.class);

    public void logStart(IntegrationRequest request, String targetName,
                         TargetProperties.LoggingProps loggingProps) {
        SpanContext ctx = spanContext();
        log.info("integration.call.start",
                kv("event",            "integration.call.start"),
                kv("target",           targetName),
                kv("method",           request.method()),
                kv("path",             request.path()),
                kv("idempotency_key",  request.idempotencyKey()),
                kv("trace_id",         ctx.isValid() ? ctx.getTraceId() : "none"),
                kv("span_id",          ctx.isValid() ? ctx.getSpanId()  : "none"));
    }

    public void logRetry(String targetName, int attemptNumber, long delayMs, Throwable lastError,
                         TargetProperties.LoggingProps loggingProps) {
        SpanContext ctx = spanContext();
        log.warn("integration.call.retry",
                kv("event",       "integration.call.retry"),
                kv("target",      targetName),
                kv("attempt",     attemptNumber),
                kv("delay_ms",    delayMs),
                kv("error_class", lastError != null ? lastError.getClass().getName() : "unknown"),
                kv("trace_id",    ctx.isValid() ? ctx.getTraceId() : "none"),
                kv("span_id",     ctx.isValid() ? ctx.getSpanId()  : "none"));
    }

    public void logSuccess(IntegrationResponse response, String targetName, long latencyMs,
                           TargetProperties.LoggingProps loggingProps) {
        SpanContext ctx = spanContext();
        log.info("integration.call.success",
                kv("event",         "integration.call.success"),
                kv("target",        targetName),
                kv("status_code",   response.statusCode()),
                kv("attempt_count", response.attemptCount()),
                kv("latency_ms",    latencyMs),
                kv("trace_id",      ctx.isValid() ? ctx.getTraceId() : "none"),
                kv("span_id",       ctx.isValid() ? ctx.getSpanId()  : "none"));
    }

    public void logFailure(IntegrationException e, String targetName, long latencyMs,
                           TargetProperties.LoggingProps loggingProps) {
        Throwable root = e.getCause() != null ? e.getCause() : e;
        SpanContext ctx = spanContext();
        log.error("integration.call.failure",
                kv("event",         "integration.call.failure"),
                kv("target",        targetName),
                kv("attempt_count", e.getAttemptCount()),
                kv("error_class",   root.getClass().getName()),
                kv("latency_ms",    latencyMs),
                kv("trace_id",      ctx.isValid() ? ctx.getTraceId() : "none"),
                kv("span_id",       ctx.isValid() ? ctx.getSpanId()  : "none"));
    }

    // Applies redaction regex to a JSON body string before logging.
    public String redact(String json, TargetProperties.LoggingProps loggingProps) {
        if (json == null || loggingProps == null) return json;
        List<String> fields = loggingProps.getRedact();
        if (fields == null || fields.isEmpty()) return json;

        String result = json;
        for (String field : fields) {
            result = Pattern.compile("(\"" + Pattern.quote(field) + "\"\\s*:\\s*)\"[^\"]*\"",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(result)
                    .replaceAll("$1\"[REDACTED]\"");
        }
        return result;
    }

    private SpanContext spanContext() {
        return Span.current().getSpanContext();
    }

    private Object kv(String key, Object value) {
        return StructuredArguments.kv(key, value);
    }
}
