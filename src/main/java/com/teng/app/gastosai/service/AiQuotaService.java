package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.AiUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiManagedProperties managedProps;
    private final AiUsageRepository aiUsageRepository;
    private final EntitlementService entitlementService;

    @Transactional(readOnly = true)
    public void assertWithinQuota(User user, AiFeature feature) {
        if (!managedProps.isAllowSharedKey() || !managedProps.isFeaturesEnabled()) {
            return;
        }
        EntitlementService.Entitlements entitlements = entitlementService.describe(user);
        if (entitlements.admin()) {
            return;
        }
        PlanKey plan = entitlements.plan();
        LocalDateTime monthStart = startOfCurrentMonth();

        long used = aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(
                user.getId(), AiUsageStatus.SUCCESS, monthStart);
        int cap = monthlyCapFor(plan);
        if (used >= cap) {
            throw new AiQuotaExceededException();
        }

        if (feature.isVision()) {
            long visionUsed = aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                    user.getId(), feature, AiUsageStatus.SUCCESS, monthStart);
            int visionCap = visionCapFor(plan);
            if (visionUsed >= visionCap) {
                throw new AiQuotaExceededException();
            }
        }
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

    private LocalDateTime startOfCurrentMonth() {
        YearMonth ym = YearMonth.now();
        return ym.atDay(1).atStartOfDay();
    }
}
