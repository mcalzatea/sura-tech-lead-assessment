package com.sura.integration.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class IntegrationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, String> cbStateMap = new ConcurrentHashMap<>();

    public IntegrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void initCircuitBreakerGauges(String targetName) {
        cbStateMap.put(targetName, "CLOSED");
        for (String state : List.of("CLOSED", "OPEN", "HALF_OPEN")) {
            Gauge.builder("integration.client.cb.state",
                            cbStateMap,
                            m -> state.equals(m.getOrDefault(targetName, "CLOSED")) ? 1.0 : 0.0)
                    .tag("target", targetName)
                    .tag("state", state)
                    .register(registry);
        }
    }

    public void updateCbState(String targetName, CircuitBreaker.State state) {
        cbStateMap.put(targetName, state.name());
    }

    public void incrementRetries(String targetName) {
        Counter.builder("integration.client.retries")
                .tag("target", targetName)
                .register(registry)
                .increment();
    }

    public void recordCall(String targetName, String outcome, long latencyMs) {
        Counter.builder("integration.client.calls")
                .tag("target", targetName)
                .tag("outcome", outcome)
                .register(registry)
                .increment();

        Timer.builder("integration.client.duration")
                .tag("target", targetName)
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofMillis(latencyMs));
    }
}
