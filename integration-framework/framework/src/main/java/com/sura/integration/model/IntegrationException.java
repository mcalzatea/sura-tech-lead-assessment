package com.sura.integration.model;

public class IntegrationException extends RuntimeException {

    private final String target;
    private final int    attemptCount;
    private final String traceId;
    private final int    statusCode;   // 0 for non-HTTP exceptions

    private IntegrationException(String target, int attemptCount, String traceId,
                                  int statusCode, Throwable cause) {
        super(buildMessage(target, attemptCount, statusCode), cause);
        this.target       = target;
        this.attemptCount = attemptCount;
        this.traceId      = traceId;
        this.statusCode   = statusCode;
    }

    public static IntegrationException of(String target, int attempts, Throwable cause) {
        return new IntegrationException(target, attempts, "unknown", 0, cause);
    }

    public static IntegrationException of(String target, int attempts, int statusCode, Throwable cause) {
        return new IntegrationException(target, attempts, "unknown", statusCode, cause);
    }

    public static IntegrationException of(String target, int attempts, String traceId, Throwable cause) {
        return new IntegrationException(target, attempts, traceId, 0, cause);
    }

    public IntegrationException withTraceId(String traceId) {
        return new IntegrationException(target, attemptCount, traceId, statusCode, getCause());
    }

    public String getTarget()       { return target; }
    public int    getAttemptCount() { return attemptCount; }
    public String getTraceId()      { return traceId; }
    public int    getStatusCode()   { return statusCode; }

    private static String buildMessage(String target, int attempts, int statusCode) {
        return statusCode > 0
                ? String.format("Integration call to '%s' failed after %d attempt(s) [HTTP %d]", target, attempts, statusCode)
                : String.format("Integration call to '%s' failed after %d attempt(s)", target, attempts);
    }
}
