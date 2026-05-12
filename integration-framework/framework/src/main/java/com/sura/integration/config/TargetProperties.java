package com.sura.integration.config;

import java.time.Duration;
import java.util.List;

public class TargetProperties {

    private String baseUrl;
    private TimeoutProps timeout;
    private RetryProps retry;
    private CircuitBreakerProps circuitBreaker;
    private IdempotencyProps idempotency;
    private LoggingProps logging;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public TimeoutProps getTimeout() { return timeout; }
    public void setTimeout(TimeoutProps timeout) { this.timeout = timeout; }

    public RetryProps getRetry() { return retry; }
    public void setRetry(RetryProps retry) { this.retry = retry; }

    public CircuitBreakerProps getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerProps circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public IdempotencyProps getIdempotency() { return idempotency; }
    public void setIdempotency(IdempotencyProps idempotency) { this.idempotency = idempotency; }

    public LoggingProps getLogging() { return logging; }
    public void setLogging(LoggingProps logging) { this.logging = logging; }

    // -------------------------------------------------------------------------

    public static class TimeoutProps {
        private Duration connect = Duration.ofSeconds(1);
        private Duration read    = Duration.ofSeconds(3);

        public Duration getConnect() { return connect; }
        public void setConnect(Duration connect) { this.connect = connect; }

        public Duration getRead() { return read; }
        public void setRead(Duration read) { this.read = read; }
    }

    public static class RetryProps {
        private int maxAttempts = 3;
        private Duration baseBackoff = Duration.ofMillis(200);
        private Duration maxBackoff  = Duration.ofSeconds(2);
        private List<Integer> retryOnStatus     = List.of(502, 503, 504);
        private List<String>  retryOnExceptions = List.of("TimeoutException", "IOException");

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public Duration getBaseBackoff() { return baseBackoff; }
        public void setBaseBackoff(Duration baseBackoff) { this.baseBackoff = baseBackoff; }

        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }

        public List<Integer> getRetryOnStatus() { return retryOnStatus; }
        public void setRetryOnStatus(List<Integer> retryOnStatus) { this.retryOnStatus = retryOnStatus; }

        public List<String> getRetryOnExceptions() { return retryOnExceptions; }
        public void setRetryOnExceptions(List<String> retryOnExceptions) { this.retryOnExceptions = retryOnExceptions; }
    }

    public static class CircuitBreakerProps {
        private float    failureRateThreshold    = 50.0f;
        private int      slidingWindowSize        = 10;
        private Duration waitDurationInOpenState  = Duration.ofSeconds(30);
        private int      permittedCallsInHalfOpen = 3;

        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

        public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) { this.waitDurationInOpenState = waitDurationInOpenState; }

        public int getPermittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) { this.permittedCallsInHalfOpen = permittedCallsInHalfOpen; }
    }

    public static class IdempotencyProps {
        private Duration ttl          = Duration.ofHours(24);
        private String   headerName   = "Idempotency-Key";
        private boolean  autoGenerate = true;

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }

        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }

        public boolean isAutoGenerate() { return autoGenerate; }
        public void setAutoGenerate(boolean autoGenerate) { this.autoGenerate = autoGenerate; }
    }

    public static class LoggingProps {
        private List<String> redact    = List.of("password", "ssn", "card_number", "authorization");
        private boolean      logBodies = false;

        public List<String> getRedact() { return redact; }
        public void setRedact(List<String> redact) { this.redact = redact; }

        public boolean isLogBodies() { return logBodies; }
        public void setLogBodies(boolean logBodies) { this.logBodies = logBodies; }
    }
}
