package com.sura.flaky.controller;

import com.sura.flaky.mode.FailureModeConfig;
import com.sura.flaky.mode.FailureModeService;
import com.sura.flaky.mode.FailureModeType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final FailureModeService failureModeService;

    public AdminController(FailureModeService failureModeService) {
        this.failureModeService = failureModeService;
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(@RequestBody ModeRequest request) {
        String normalised = request.mode().toUpperCase().replace('-', '_');
        FailureModeType type = FailureModeType.valueOf(normalised);

        int intValue = 0;
        double doubleValue = 0.0;
        if (request.value() != null) {
            if (type == FailureModeType.INTERMITTENT_P) {
                doubleValue = request.value().doubleValue();
            } else {
                intValue = request.value().intValue();
            }
        }

        FailureModeConfig config = new FailureModeConfig(type, intValue, doubleValue, new AtomicInteger(0));
        failureModeService.setMode(config);

        return ResponseEntity.ok(modeResponse(config));
    }

    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode() {
        return ResponseEntity.ok(modeResponse(failureModeService.getMode()));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        failureModeService.reset();
        return ResponseEntity.ok(Map.of("mode", "healthy"));
    }

    private Map<String, Object> modeResponse(FailureModeConfig cfg) {
        return Map.of(
                "mode",      cfg.type().name().toLowerCase(),
                "intValue",  cfg.intValue(),
                "callCount", cfg.callCount().get());
    }

    public record ModeRequest(String mode, Number value) {}
}
