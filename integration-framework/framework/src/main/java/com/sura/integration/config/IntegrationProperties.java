package com.sura.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "integration")
public class IntegrationProperties {

    private TargetProperties defaults = new TargetProperties();
    private Map<String, TargetProperties> targets = new HashMap<>();

    public TargetProperties getDefaults() { return defaults; }
    public void setDefaults(TargetProperties defaults) { this.defaults = defaults; }

    public Map<String, TargetProperties> getTargets() { return targets; }
    public void setTargets(Map<String, TargetProperties> targets) { this.targets = targets; }

    /**
     * Merges the target-specific block over the global defaults.
     * Any null field in the target-specific block inherits the default value.
     */
    public TargetProperties resolve(String targetName) {
        TargetProperties override = targets.getOrDefault(targetName, new TargetProperties());
        TargetProperties result = new TargetProperties();

        result.setBaseUrl(coalesce(override.getBaseUrl(), defaults.getBaseUrl()));
        result.setTimeout(mergeTimeout(override.getTimeout(), defaults.getTimeout()));
        result.setRetry(mergeRetry(override.getRetry(), defaults.getRetry()));
        result.setCircuitBreaker(mergeCb(override.getCircuitBreaker(), defaults.getCircuitBreaker()));
        result.setIdempotency(mergeIdempotency(override.getIdempotency(), defaults.getIdempotency()));
        result.setLogging(mergeLogging(override.getLogging(), defaults.getLogging()));

        return result;
    }

    private TargetProperties.TimeoutProps mergeTimeout(
            TargetProperties.TimeoutProps o, TargetProperties.TimeoutProps d) {
        TargetProperties.TimeoutProps t = new TargetProperties.TimeoutProps();
        if (d != null) { t.setConnect(d.getConnect()); t.setRead(d.getRead()); }
        if (o != null) {
            if (o.getConnect() != null) t.setConnect(o.getConnect());
            if (o.getRead()    != null) t.setRead(o.getRead());
        }
        return t;
    }

    private TargetProperties.RetryProps mergeRetry(
            TargetProperties.RetryProps o, TargetProperties.RetryProps d) {
        TargetProperties.RetryProps r = new TargetProperties.RetryProps();
        if (d != null) {
            r.setMaxAttempts(d.getMaxAttempts());
            r.setBaseBackoff(d.getBaseBackoff());
            r.setMaxBackoff(d.getMaxBackoff());
            r.setRetryOnStatus(d.getRetryOnStatus());
            r.setRetryOnExceptions(d.getRetryOnExceptions());
        }
        if (o != null) {
            if (o.getMaxAttempts() > 0)           r.setMaxAttempts(o.getMaxAttempts());
            if (o.getBaseBackoff() != null)        r.setBaseBackoff(o.getBaseBackoff());
            if (o.getMaxBackoff()  != null)        r.setMaxBackoff(o.getMaxBackoff());
            if (o.getRetryOnStatus() != null)      r.setRetryOnStatus(o.getRetryOnStatus());
            if (o.getRetryOnExceptions() != null)  r.setRetryOnExceptions(o.getRetryOnExceptions());
        }
        return r;
    }

    private TargetProperties.CircuitBreakerProps mergeCb(
            TargetProperties.CircuitBreakerProps o, TargetProperties.CircuitBreakerProps d) {
        TargetProperties.CircuitBreakerProps cb = new TargetProperties.CircuitBreakerProps();
        if (d != null) {
            cb.setFailureRateThreshold(d.getFailureRateThreshold());
            cb.setSlidingWindowSize(d.getSlidingWindowSize());
            cb.setWaitDurationInOpenState(d.getWaitDurationInOpenState());
            cb.setPermittedCallsInHalfOpen(d.getPermittedCallsInHalfOpen());
        }
        if (o != null) {
            if (o.getFailureRateThreshold() > 0)         cb.setFailureRateThreshold(o.getFailureRateThreshold());
            if (o.getSlidingWindowSize() > 0)             cb.setSlidingWindowSize(o.getSlidingWindowSize());
            if (o.getWaitDurationInOpenState() != null)   cb.setWaitDurationInOpenState(o.getWaitDurationInOpenState());
            if (o.getPermittedCallsInHalfOpen() > 0)      cb.setPermittedCallsInHalfOpen(o.getPermittedCallsInHalfOpen());
        }
        return cb;
    }

    private TargetProperties.IdempotencyProps mergeIdempotency(
            TargetProperties.IdempotencyProps o, TargetProperties.IdempotencyProps d) {
        TargetProperties.IdempotencyProps ip = new TargetProperties.IdempotencyProps();
        if (d != null) {
            ip.setTtl(d.getTtl());
            ip.setHeaderName(d.getHeaderName());
            ip.setAutoGenerate(d.isAutoGenerate());
        }
        if (o != null) {
            if (o.getTtl()        != null) ip.setTtl(o.getTtl());
            if (o.getHeaderName() != null) ip.setHeaderName(o.getHeaderName());
        }
        return ip;
    }

    private TargetProperties.LoggingProps mergeLogging(
            TargetProperties.LoggingProps o, TargetProperties.LoggingProps d) {
        TargetProperties.LoggingProps lp = new TargetProperties.LoggingProps();
        if (d != null) {
            lp.setRedact(d.getRedact());
            lp.setLogBodies(d.isLogBodies());
        }
        if (o != null) {
            if (o.getRedact() != null) lp.setRedact(o.getRedact());
            lp.setLogBodies(o.isLogBodies());
        }
        return lp;
    }

    private static <T> T coalesce(T override, T fallback) {
        return override != null ? override : fallback;
    }
}
