package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiUsageResponse;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiUsageController {

    private final AiQuotaService aiQuotaService;
    private final EntitlementService entitlementService;

    @GetMapping("/usage")
    public AiUsageResponse usage(@AuthenticationPrincipal User user) {
        PlanKey plan = entitlementService.describe(user).plan();
        LocalDateTime resetsAt = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay();

        long used = aiQuotaService.usedThisMonth(user.getId());
        long visionUsed = aiQuotaService.visionUsedThisMonth(user.getId());
        int limit = aiQuotaService.monthlyCap(plan);
        int visionLimit = aiQuotaService.visionCap(plan);
        long remaining = Math.max(0, limit - used);

        return new AiUsageResponse(
                plan.name(), used, limit, remaining, visionUsed, visionLimit,
                aiQuotaService.managedActive(), resetsAt);
    }
}
