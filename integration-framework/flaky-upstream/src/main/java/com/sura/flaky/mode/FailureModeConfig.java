package com.sura.flaky.mode;

import java.util.concurrent.atomic.AtomicInteger;

public record FailureModeConfig(
        FailureModeType type,
        int intValue,
        double doubleValue,
        AtomicInteger callCount
) {
    public static FailureModeConfig healthy() {
        return new FailureModeConfig(FailureModeType.HEALTHY, 0, 0.0, new AtomicInteger(0));
    }
}
