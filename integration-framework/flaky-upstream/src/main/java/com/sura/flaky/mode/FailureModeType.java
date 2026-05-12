package com.sura.flaky.mode;

public enum FailureModeType {
    HEALTHY,
    TRANSIENT_5XX,
    ALWAYS_503,
    LATENCY_MS,
    INTERMITTENT_P
}
