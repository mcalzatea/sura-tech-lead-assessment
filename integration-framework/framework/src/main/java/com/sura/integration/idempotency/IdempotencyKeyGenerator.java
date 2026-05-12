package com.sura.integration.idempotency;

import com.fasterxml.uuid.Generators;
import org.springframework.stereotype.Component;

/**
 * Generates UUID v7 (time-ordered, monotonic) idempotency keys.
 * UUID v7 improves Redis memory locality and simplifies log correlation by time.
 */
@Component
public class IdempotencyKeyGenerator {

    public String generate() {
        return Generators.timeBasedEpochGenerator().generate().toString();
    }
}
