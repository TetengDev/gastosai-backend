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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiQuotaService {

    /** Features that consume the monthly cap (chat + vision); insights are excluded. */
    private static final Set<AiFeature> QUOTA_BEARING = EnumSet.allOf(AiFeature.class).stream()
            .filter(AiFeature::countsTowardQuota)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(AiFeature.class)));

    private final AiManagedProperties managedProps;
    private final AiUsageRepository aiUsageRepository;
    private final EntitlementService entitlementService;

    @Transactional(readOnly = true)
    public void assertWithinQuota(User user, AiFeature feature) {
        if (!user.isAdmin() && globalDailyUsed() >= managedProps.getGlobalDailyMax()) {
            throw new AiQuotaExceededException();
        }
        if (QUOTA_BEARING.contains(feature)) {
            long used = usedThisMonth(user.getId());
            if (used >= managedProps.getAbsoluteMonthlyCap()) {
                throw new AiQuotaExceededException();
            }
        }
        if (!managedProps.isAllowSharedKey() || !managedProps.isFeaturesEnabled()) {
            return;
        }
        EntitlementService.Entitlements entitlements = entitlementService.describe(user);
        if (entitlements.admin()) {
            return;
        }
        PlanKey plan = entitlements.plan();

        long used = usedThisMonth(user.getId());
        if (used >= monthlyCap(plan)) {
            throw new AiQuotaExceededException();
        }

        if (feature.isVision() && visionUsedThisMonth(user.getId()) >= visionCap(plan)) {
            throw new AiQuotaExceededException();
        }
    }

    /** True when managed (shared-key) AI is active, i.e. quotas are enforced. */
    public boolean managedActive() {
        return managedProps.isAllowSharedKey() && managedProps.isFeaturesEnabled();
    }

    /** Quota-bearing successful AI requests (chat + vision; excludes insights) this calendar month. */
    @Transactional(readOnly = true)
    public long usedThisMonth(Long userId) {
        return aiUsageRepository.countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(
                userId, AiUsageStatus.SUCCESS, QUOTA_BEARING, startOfCurrentMonth());
    }

    @Transactional(readOnly = true)
    public long visionUsedThisMonth(Long userId) {
        return aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                userId, AiFeature.RECEIPT_ANALYSIS, AiUsageStatus.SUCCESS, startOfCurrentMonth());
    }

    public int monthlyCap(PlanKey plan) {
        return switch (plan) {
            case PREMIUM -> managedProps.getQuotaPremium();
            case TRIAL -> managedProps.getQuotaTrial();
            default -> managedProps.getQuotaFree();
        };
    }

    public int visionCap(PlanKey plan) {
        return switch (plan) {
            case PREMIUM -> managedProps.getVisionPremium();
            case TRIAL -> managedProps.getVisionTrial();
            default -> managedProps.getVisionFree();
        };
    }

    @Transactional(readOnly = true)
    public long globalDailyUsed() {
        return aiUsageRepository.countByStatusAndCreatedAtAfter(AiUsageStatus.SUCCESS, startOfToday());
    }

    private LocalDateTime startOfCurrentMonth() {
        YearMonth ym = YearMonth.now();
        return ym.atDay(1).atStartOfDay();
    }

    private LocalDateTime startOfToday() {
        return LocalDate.now().atStartOfDay();
    }
}
