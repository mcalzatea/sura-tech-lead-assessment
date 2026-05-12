package com.sura.flaky.mode;

import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FailureModeService {

    private final AtomicReference<FailureModeConfig> current =
            new AtomicReference<>(FailureModeConfig.healthy());

    public void setMode(FailureModeConfig config) {
        current.set(config);
    }

    public FailureModeConfig getMode() {
        return current.get();
    }

    public void reset() {
        current.set(FailureModeConfig.healthy());
    }

    public WorkResult evaluate() {
        FailureModeConfig cfg = current.get();
        return switch (cfg.type()) {
            case HEALTHY -> {
                sleep(randomMs(50, 100));
                yield WorkResult.ok();
            }
            case TRANSIENT_5XX -> {
                int n = cfg.callCount().incrementAndGet();
                if (n <= cfg.intValue()) {
                    yield WorkResult.fail(503);
                }
                sleep(randomMs(50, 100));
                yield WorkResult.ok();
            }
            case ALWAYS_503 -> WorkResult.fail(503);
            case LATENCY_MS -> {
                sleep(cfg.intValue());
                yield WorkResult.ok();
            }
            case INTERMITTENT_P -> {
                if (ThreadLocalRandom.current().nextDouble() < cfg.doubleValue()) {
                    yield WorkResult.fail(503);
                }
                sleep(randomMs(50, 100));
                yield WorkResult.ok();
            }
        };
    }

    private static long randomMs(int min, int max) {
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
