package com.sura.flaky.controller;

import com.sura.flaky.mode.FailureModeService;
import com.sura.flaky.mode.WorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class WorkController {

    private static final Logger log = LoggerFactory.getLogger(WorkController.class);

    private final FailureModeService failureModeService;

    public WorkController(FailureModeService failureModeService) {
        this.failureModeService = failureModeService;
    }

    @PostMapping("/work")
    public ResponseEntity<Map<String, Object>> work(
            @RequestBody(required = false) Object body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        WorkResult result = failureModeService.evaluate();
        String requestId = UUID.randomUUID().toString();
        String modeName = failureModeService.getMode().type().name().toLowerCase();

        log.info("upstream.work.called mode={} statusCode={} idempotencyKey={} requestId={}",
                modeName, result.statusCode(), idempotencyKey, requestId);

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "requestId", requestId));
        }

        return ResponseEntity.status(result.statusCode()).body(Map.of(
                "status", "error",
                "message", "Simulated failure"));
    }
}
