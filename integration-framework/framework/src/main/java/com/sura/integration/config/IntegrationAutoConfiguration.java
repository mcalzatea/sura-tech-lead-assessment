package com.sura.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sura.integration.core.IntegrationExecutor;
import com.sura.integration.idempotency.IdempotencyKeyGenerator;
import com.sura.integration.idempotency.IdempotencyStore;
import com.sura.integration.idempotency.RedisIdempotencyStore;
import com.sura.integration.model.IntegrationException;
import com.sura.integration.observability.IntegrationLogger;
import com.sura.integration.observability.IntegrationMetrics;
import com.sura.integration.observability.IntegrationSpanDecorator;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@AutoConfiguration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationAutoConfiguration {

    @Bean
    public Tracer integrationTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.sura.integration", "1.0.0");
    }

    @Bean
    public IntegrationSpanDecorator integrationSpanDecorator(Tracer integrationTracer,
                                                              OpenTelemetry openTelemetry) {
        return new IntegrationSpanDecorator(integrationTracer, openTelemetry);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public IntegrationMetrics integrationMetrics(MeterRegistry meterRegistry) {
        return new IntegrationMetrics(meterRegistry);
    }

    @Bean
    public IntegrationLogger integrationLogger() {
        return new IntegrationLogger();
    }

    @Bean
    public RetryRegistry retryRegistry(IntegrationProperties props) {
        Map<String, RetryConfig> configs = new HashMap<>();
        configs.put("default", buildRetryConfig(props.getDefaults()));
        props.getTargets().forEach((name, target) ->
                configs.put(name, buildRetryConfig(props.resolve(name))));
        return RetryRegistry.of(configs);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(IntegrationProperties props) {
        Map<String, CircuitBreakerConfig> configs = new HashMap<>();
        configs.put("default", buildCbConfig(props.getDefaults()));
        props.getTargets().forEach((name, target) ->
                configs.put(name, buildCbConfig(props.resolve(name))));
        return CircuitBreakerRegistry.of(configs);
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(IntegrationProperties props) {
        Map<String, TimeLimiterConfig> configs = new HashMap<>();
        configs.put("default", buildTlConfig(props.getDefaults()));
        props.getTargets().forEach((name, target) ->
                configs.put(name, buildTlConfig(props.resolve(name))));
        return TimeLimiterRegistry.of(configs);
    }

    @Bean
    public Map<String, IntegrationExecutor> integrationExecutors(
            IntegrationProperties props,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            IntegrationSpanDecorator spanDecorator,
            IntegrationMetrics metrics,
            IntegrationLogger logger) {

        Map<String, IntegrationExecutor> executors = new HashMap<>();
        props.getTargets().forEach((name, target) -> {
            TargetProperties resolved = props.resolve(name);
            RestClient restClient = buildRestClient(resolved);
            executors.put(name, new IntegrationExecutor(
                    name,
                    resolved.getBaseUrl(),
                    resolved,
                    restClient,
                    retryRegistry,
                    circuitBreakerRegistry,
                    timeLimiterRegistry,
                    spanDecorator,
                    metrics,
                    logger));
        });
        return executors;
    }

    @Bean
    public IdempotencyKeyGenerator idempotencyKeyGenerator() {
        return new IdempotencyKeyGenerator();
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public IdempotencyStore idempotencyStore(StringRedisTemplate redisTemplate,
                                             ObjectMapper objectMapper,
                                             IdempotencyKeyGenerator keyGenerator) {
        return new RedisIdempotencyStore(redisTemplate, objectMapper, keyGenerator);
    }

    // -------------------------------------------------------------------------

    private RetryConfig buildRetryConfig(TargetProperties props) {
        TargetProperties.RetryProps retry = props.getRetry();
        if (retry == null) retry = new TargetProperties.RetryProps();

        final TargetProperties.RetryProps r = retry;
        return RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        r.getBaseBackoff().toMillis(),
                        2.0,
                        r.getMaxBackoff().toMillis()))
                .retryOnException(ex -> {
                    if (!(ex instanceof IntegrationException ie)) return false;
                    int status = ie.getStatusCode();
                    if (status >= 400 && status < 500) return false;
                    if (status > 0 && r.getRetryOnStatus().contains(status)) return true;
                    Throwable cause = ex.getCause();
                    return cause != null && r.getRetryOnExceptions().stream()
                            .anyMatch(n -> cause.getClass().getSimpleName().equals(n));
                })
                .build();
    }

    private CircuitBreakerConfig buildCbConfig(TargetProperties props) {
        TargetProperties.CircuitBreakerProps cb = props.getCircuitBreaker();
        if (cb == null) cb = new TargetProperties.CircuitBreakerProps();
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .failureRateThreshold(cb.getFailureRateThreshold())
                .waitDurationInOpenState(cb.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpen())
                .build();
    }

    private TimeLimiterConfig buildTlConfig(TargetProperties props) {
        TargetProperties.TimeoutProps timeout = props.getTimeout();
        if (timeout == null) timeout = new TargetProperties.TimeoutProps();
        return TimeLimiterConfig.custom()
                .timeoutDuration(timeout.getRead())
                .cancelRunningFuture(true)
                .build();
    }

    private RestClient buildRestClient(TargetProperties props) {
        TargetProperties.TimeoutProps timeout = props.getTimeout();
        if (timeout == null) timeout = new TargetProperties.TimeoutProps();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.getConnect().toMillis());
        factory.setReadTimeout((int) timeout.getRead().toMillis());

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (props.getBaseUrl() != null) {
            builder.baseUrl(props.getBaseUrl());
        }
        return builder.build();
    }
}
