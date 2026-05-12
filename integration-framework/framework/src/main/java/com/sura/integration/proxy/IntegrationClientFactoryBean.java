package com.sura.integration.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sura.integration.annotation.IntegrationClient;
import com.sura.integration.config.IntegrationProperties;
import com.sura.integration.config.TargetProperties;
import com.sura.integration.core.IntegrationExecutor;
import com.sura.integration.idempotency.IdempotencyStore;
import com.sura.integration.observability.IntegrationLogger;
import com.sura.integration.observability.IntegrationMetrics;
import com.sura.integration.observability.IntegrationSpanDecorator;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AbstractFactoryBean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Proxy;
import java.util.Map;

public class IntegrationClientFactoryBean<T> extends AbstractFactoryBean<T> {

    private final Class<T> interfaceClass;

    @Autowired
    private IntegrationProperties properties;

    @Autowired
    private Map<String, IntegrationExecutor> executors;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    @Autowired
    private IntegrationSpanDecorator spanDecorator;

    @Autowired
    private IntegrationMetrics metrics;

    @Autowired
    private IntegrationLogger logger;

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private IdempotencyStore idempotencyStore;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public IntegrationClientFactoryBean(Class<T> interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    @Override
    public Class<?> getObjectType() {
        return interfaceClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T createInstance() {
        IntegrationClient ann = interfaceClass.getAnnotation(IntegrationClient.class);
        TargetProperties config = properties.resolve(ann.config());
        // Resolve Spring property placeholders (e.g. ${integration.targets.x.base-url})
        String resolvedBaseUrl = environment.resolvePlaceholders(ann.baseUrl());
        config.setBaseUrl(resolvedBaseUrl);

        IntegrationExecutor executor = executors.computeIfAbsent(
                ann.config(),
                key -> buildExecutor(ann.name(), resolvedBaseUrl, config));

        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{ interfaceClass },
                new IntegrationClientInterceptor(executor, idempotencyStore, objectMapper, config, ann.name()));
    }

    private IntegrationExecutor buildExecutor(String targetName, String baseUrl, TargetProperties cfg) {
        TargetProperties.TimeoutProps timeout = cfg.getTimeout() != null
                ? cfg.getTimeout()
                : new TargetProperties.TimeoutProps();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.getConnect().toMillis());
        factory.setReadTimeout((int) timeout.getRead().toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        return new IntegrationExecutor(
                targetName, baseUrl, cfg, restClient,
                retryRegistry, circuitBreakerRegistry, timeLimiterRegistry,
                spanDecorator, metrics, logger);
    }
}
