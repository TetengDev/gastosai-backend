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
    private final AppEventService appEventService;

    // Not readOnly: on the global-cap path this records an abuse-trip event. The method is
    // otherwise read-only, but marking it so is misleading now that it has a write side-effect.
    @Transactional
    public void assertWithinQuota(User user, AiFeature feature) {
        // `globalDailyMax` is a *shared pool*: one counter for the whole platform, spent against
        // our key. A user calling the provider on their own key spends none of it, so charging
        // them against that pool lets one heavy caller lock out everybody else — the bug TEN-244
        // reports. Hence the managedActive() gate here, and only here.
        //
        // `absoluteMonthlyCap` below is deliberately NOT gated. It is per-user
        // (attemptedThisMonth(user.getId())) and it guards our backend rather than our provider
        // bill: parsing, storage and database work happen on every request no matter who pays the
        // provider. It is an abuse valve, not a budget counter, so it applies in
        // bring-your-own-key mode too — which is what
        // AiQuotaServiceTest.absoluteCap_blocks_whenAtCap_byoMode asserts.
        //
        // The two backend-guard caps — this one and the global daily cap above — count *attempts*:
        // SUCCESS and FAILED rows alike (TEN-409). A refused upload still costs a header parse, and
        // an accepted-but-hostile one costs a bounded decode up to VisionService.MAX_DECODE_PX, so
        // a failure consumes the very resource these caps exist to protect. Counting successes only
        // let a client repeat a refused or expensive request without limit.
        //
        // The per-plan entitlement quotas further down deliberately keep counting successes only:
        // those meter what the user paid for, and a scan that failed for our reasons — a provider
        // outage, a bug — is not a scan the user received. usedThisMonth() is the SUCCESS-only
        // count they are built on and the one /ai/usage reports; attemptedThisMonth() is the
        // attempt count, and no per-plan cap reads it.
        //
        // A quota refusal itself writes no AiUsage row — every caller asserts before entering the
        // recorded block — so a tripped cap does not feed itself.
        if (managedActive()
                && !user.isAdmin()
                && globalDailyUsed() >= managedProps.getGlobalDailyMax()) {
            appEventService.recordAbuseTrip("AI_GLOBAL_CAP", user.getId(), "/ai",
                    "Global daily AI request cap reached");
            throw new AiQuotaExceededException();
        }
        if (QUOTA_BEARING.contains(feature)) {
            long attempted = attemptedThisMonth(user.getId());
            if (attempted >= managedProps.getAbsoluteMonthlyCap()) {
                throw new AiQuotaExceededException();
            }
        }
        // Per-plan entitlement quotas are shared-key concepts; they do not apply on a user's
        // own key. This is the guard that stood here before TEN-244, unchanged in meaning.
        if (!managedActive()) {
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

    /**
     * Quota-bearing <em>successful</em> AI requests (chat + vision; excludes insights) this
     * calendar month. This is what the per-plan quotas meter and what {@code /ai/usage} reports:
     * a request that failed for our reasons must not cost the user a paid-for scan. For the
     * backend-guard caps use {@link #attemptedThisMonth(Long)} instead.
     */
    @Transactional(readOnly = true)
    public long usedThisMonth(Long userId) {
        return aiUsageRepository.countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(
                userId, AiUsageStatus.SUCCESS, QUOTA_BEARING, startOfCurrentMonth());
    }

    /**
     * Quota-bearing AI <em>attempts</em> this calendar month — SUCCESS and FAILED alike. Backs the
     * absolute monthly cap only; see {@link #assertWithinQuota} for why that cap counts failures
     * and the per-plan quotas do not.
     */
    @Transactional(readOnly = true)
    public long attemptedThisMonth(Long userId) {
        return aiUsageRepository.countByUserIdAndFeatureInAndCreatedAtAfter(
                userId, QUOTA_BEARING, startOfCurrentMonth());
    }

    /** Successful vision requests this month — per-plan sub-cap, so successes only. */
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

    /**
     * Platform-wide AI attempts today — SUCCESS and FAILED alike. Like the absolute monthly cap
     * this is a backend guard, so a failed request spends the shared pool just as a successful one
     * does. The name is kept for the callers that read it; it counts attempts (TEN-409).
     */
    public long globalDailyUsed() {
        return aiUsageRepository.countByCreatedAtAfter(startOfToday());
    }

    private LocalDateTime startOfCurrentMonth() {
        YearMonth ym = YearMonth.now();
        return ym.atDay(1).atStartOfDay();
    }

    private LocalDateTime startOfToday() {
        return LocalDate.now().atStartOfDay();
    }
}
