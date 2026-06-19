package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.dto.AiUsageResponse;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AiUsageRepository;
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

    private final AiUsageRepository aiUsageRepository;
    private final AiManagedProperties managedProps;
    private final EntitlementService entitlementService;

    @GetMapping("/usage")
    public AiUsageResponse usage(@AuthenticationPrincipal User user) {
        EntitlementService.Entitlements entitlements = entitlementService.describe(user);
        PlanKey plan = entitlements.plan();

        YearMonth current = YearMonth.now();
        LocalDateTime monthStart = current.atDay(1).atStartOfDay();
        LocalDateTime resetsAt = current.plusMonths(1).atDay(1).atStartOfDay();

        long used = aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(
                user.getId(), AiUsageStatus.SUCCESS, monthStart);
        long visionUsed = aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                user.getId(), AiFeature.RECEIPT_ANALYSIS, AiUsageStatus.SUCCESS, monthStart);

        int limit = monthlyCapFor(plan);
        int visionLimit = visionCapFor(plan);
        long remaining = Math.max(0, limit - used);

        return new AiUsageResponse(plan.name(), used, limit, remaining, visionUsed, visionLimit, resetsAt);
    }

    private int monthlyCapFor(PlanKey plan) {
        return switch (plan) {
            case PREMIUM -> managedProps.getQuotaPremium();
            case TRIAL -> managedProps.getQuotaTrial();
            default -> managedProps.getQuotaFree();
        };
    }

    private int visionCapFor(PlanKey plan) {
        return switch (plan) {
            case PREMIUM -> managedProps.getVisionPremium();
            case TRIAL -> managedProps.getVisionTrial();
            default -> managedProps.getVisionFree();
        };
    }
}
