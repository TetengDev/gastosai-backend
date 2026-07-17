package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AppEventDto;
import com.teng.app.gastosai.dto.ObservabilityCost;
import com.teng.app.gastosai.dto.ObservabilityHealth;
import com.teng.app.gastosai.dto.ObservabilitySummary;
import com.teng.app.gastosai.service.ObservabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only observability dashboard data. Access enforced in SecurityConfig (`/admin/**`). */
@RestController
@RequestMapping("/admin/observability")
@RequiredArgsConstructor
public class AdminObservabilityController {

    private final ObservabilityService observabilityService;

    @GetMapping("/summary")
    public ObservabilitySummary summary() {
        return observabilityService.summary();
    }

    @GetMapping("/cost")
    public ObservabilityCost cost() {
        return observabilityService.cost();
    }

    @GetMapping("/events")
    public List<AppEventDto> events(@RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "100") int limit) {
        return observabilityService.events(type, limit);
    }

    @GetMapping("/health")
    public ObservabilityHealth health() {
        return observabilityService.health();
    }
}
