package com.sura.integration.core;

import com.fasterxml.uuid.Generators;
import com.sura.integration.config.TargetProperties;
import com.sura.integration.model.IntegrationException;
import com.sura.integration.model.IntegrationRequest;
import com.sura.integration.model.IntegrationResponse;
import com.sura.integration.observability.IntegrationLogger;
import com.sura.integration.observability.IntegrationMetrics;
import com.sura.integration.observability.IntegrationSpanDecorator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class IntegrationExecutor {

    private final String targetName;
    private final String baseUrl;
    private final TargetProperties config;
    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final IntegrationSpanDecorator spanDecorator;
    private final IntegrationMetrics metrics;
    private final IntegrationLogger logger;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public IntegrationExecutor(String targetName,
                               String baseUrl,
                               TargetProperties config,
                               RestClient restClient,
                               RetryRegistry retryRegistry,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               TimeLimiterRegistry timeLimiterRegistry,
                               IntegrationSpanDecorator spanDecorator,
                               IntegrationMetrics metrics,
                               IntegrationLogger logger) {
        this.targetName    = targetName;
        this.baseUrl       = baseUrl;
        this.config        = config;
        this.restClient    = restClient;
        this.retry         = retryRegistry.retry(targetName);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(targetName);
        this.timeLimiter   = timeLimiterRegistry.timeLimiter(targetName);
        this.spanDecorator = spanDecorator;
        this.metrics       = metrics;
        this.logger        = logger;

        metrics.initCircuitBreakerGauges(targetName);

        this.retry.getEventPublisher().onRetry(event -> {
            metrics.incrementRetries(targetName);
            logger.logRetry(targetName, event.getNumberOfRetryAttempts(),
                    event.getWaitInterval().toMillis(), event.getLastThrowable(), config.getLogging());
        });

        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                metrics.updateCbState(targetName, event.getStateTransition().getToState()));
    }

    @SuppressWarnings("unchecked")
    public <T> IntegrationResponse execute(IntegrationRequest request) {
        Map<String, String> resolvedHeaders = buildHeaders(request);
        AtomicInteger attemptCounter = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        Span span = spanDecorator.startSpan(request, targetName, baseUrl);
        Scope scope = span.makeCurrent();

        logger.logStart(request, targetName, config.getLogging());

        // Capture OTel context (including new span) for propagation to executor threads
        Context callerContext = Context.current();

        Callable<IntegrationResponse> doCall = callerContext.wrap(
                () -> doHttpCall(request, resolvedHeaders, attemptCounter.incrementAndGet()));

        Callable<IntegrationResponse> withCB =
                CircuitBreaker.decorateCallable(circuitBreaker, doCall);

        Callable<IntegrationResponse> withRetry =
                request.idempotent()
                        ? Retry.decorateCallable(retry, withCB)
                        : withCB;

        Callable<IntegrationResponse> withTimeLimiter =
                TimeLimiter.decorateFutureSupplier(timeLimiter,
                        () -> executor.submit(withRetry));

        try {
            IntegrationResponse response = withTimeLimiter.call();
            long latency = System.currentTimeMillis() - startTime;
            spanDecorator.onSuccess(span, response);
            logger.logSuccess(response, targetName, latency, config.getLogging());
            metrics.recordCall(targetName, "success", latency);
            return response;

        } catch (IntegrationException e) {
            IntegrationException enriched = e.withTraceId(span.getSpanContext().getTraceId());
            long latency = System.currentTimeMillis() - startTime;
            spanDecorator.onError(span, enriched);
            logger.logFailure(enriched, targetName, latency, config.getLogging());
            metrics.recordCall(targetName, outcomeFor(enriched), latency);
            throw enriched;

        } catch (Exception e) {
            Throwable cause = unwrap(e);
            IntegrationException ie = (cause instanceof IntegrationException iex)
                    ? iex.withTraceId(span.getSpanContext().getTraceId())
                    : IntegrationException.of(targetName, attemptCounter.get(),
                            span.getSpanContext().getTraceId(), cause);
            long latency = System.currentTimeMillis() - startTime;
            spanDecorator.onError(span, ie);
            logger.logFailure(ie, targetName, latency, config.getLogging());
            metrics.recordCall(targetName, outcomeFor(ie), latency);
            throw ie;

        } finally {
            scope.close();
            spanDecorator.endSpan(span);
        }
    }

    private Map<String, String> buildHeaders(IntegrationRequest request) {
        Map<String, String> headers = new HashMap<>(request.headers());

        TargetProperties.IdempotencyProps idempotency = config.getIdempotency();
        if (idempotency != null && request.idempotent()) {
            String key = request.idempotencyKey();
            if (key == null && idempotency.isAutoGenerate()) {
                key = Generators.timeBasedEpochGenerator().generate().toString();
            }
            if (key != null) {
                headers.put(idempotency.getHeaderName(), key);
            }
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private IntegrationResponse doHttpCall(IntegrationRequest request,
                                           Map<String, String> headers,
                                           int attempt) {
        // Propagate W3C traceparent into outbound headers
        spanDecorator.injectContext(headers);

        long start = System.currentTimeMillis();
        try {
            var reqSpec = restClient
                    .method(HttpMethod.valueOf(request.method()))
                    .uri(request.path())
                    .headers(h -> headers.forEach(h::set));

            if (request.body() != null) {
                reqSpec.body(request.body());
            }

            ResponseEntity<Object> entity = reqSpec
                    .retrieve()
                    .toEntity((Class<Object>) request.responseType());

            long latency = System.currentTimeMillis() - start;
            Map<String, String> responseHeaders = new HashMap<>();
            entity.getHeaders().forEach((k, v) -> responseHeaders.put(k, String.join(", ", v)));

            return new IntegrationResponse(
                    entity.getStatusCode().value(),
                    entity.getBody(),
                    responseHeaders,
                    attempt,
                    latency);

        } catch (HttpClientErrorException e) {
            throw IntegrationException.of(targetName, attempt, e.getStatusCode().value(), e);

        } catch (HttpServerErrorException e) {
            throw IntegrationException.of(targetName, attempt, e.getStatusCode().value(), e);

        } catch (IntegrationException e) {
            throw e;

        } catch (Exception e) {
            throw IntegrationException.of(targetName, attempt, e);
        }
    }

    private String outcomeFor(IntegrationException e) {
        if (e.getCause() instanceof CallNotPermittedException) {
            return "cb_open";
        }
        return "error";
    }

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null &&
               (t.getClass().getName().contains("ExecutionException") ||
                t.getClass().getName().contains("CompletionException"))) {
            t = t.getCause();
        }
        return t;
    }
}
