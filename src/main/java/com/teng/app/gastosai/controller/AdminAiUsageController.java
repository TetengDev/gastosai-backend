package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiUsageSummaryItem;
import com.teng.app.gastosai.service.AiUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
