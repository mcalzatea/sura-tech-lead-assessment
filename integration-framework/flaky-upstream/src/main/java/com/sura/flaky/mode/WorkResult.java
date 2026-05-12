package com.sura.flaky.mode;

public record WorkResult(boolean success, int statusCode, String message) {

    public static WorkResult ok() {
        return new WorkResult(true, 200, "OK");
    }

    public static WorkResult fail(int statusCode) {
        return new WorkResult(false, statusCode, "Simulated failure");
    }
}
