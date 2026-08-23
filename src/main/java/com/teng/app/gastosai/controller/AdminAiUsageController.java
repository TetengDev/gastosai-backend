package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiCostReport;
import com.teng.app.gastosai.dto.AiUsageSummaryItem;
import com.teng.app.gastosai.service.AiUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/admin/ai-usage")
@RequiredArgsConstructor
public class AdminAiUsageController {

    private final AiUsageService aiUsageService;

    @GetMapping("/summary")
    public List<AiUsageSummaryItem> summary() {
        return aiUsageService.monthToDateSummary();
    }

    /**
     * Cost to serve for a period, per user and per plan. Defaults to month-to-date in Asia/Manila
     * when the dates are omitted.
     */
    @GetMapping("/cost-report")
    @Operation(summary = "Cost to serve per user and per plan",
            description = "Recomputes USD cost from recorded token counts, keeping text and "
                    + "vision apart, and states the per-token prices used and when they were last "
                    + "checked. Defaults to month-to-date in Asia/Manila.")
    public AiCostReport costReport(
            @Parameter(description = "First day counted, inclusive (`YYYY-MM-DD`, Asia/Manila).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day counted, inclusive (`YYYY-MM-DD`, Asia/Manila).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return aiUsageService.costReport(from, to);
    }
}
