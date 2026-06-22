package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.config.ViewAsContext;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanFeature;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.repository.PlanFeatureRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central authority for feature access. This is the single place that answers "may this user use
 * feature X" — controllers, the {@code @RequiresFeature} interceptor, and the frontend entitlements
 * endpoint all route through here, so access rules are never scattered across the codebase.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final MonetizationProperties monetizationProperties;
    private final SubscriptionPlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    @Transactional(readOnly = true)
    public boolean canAccessFeature(User user, FeatureKey feature) {
        PlanKey simulated = ViewAsContext.plan();
        if (simulated != null) {
            return featuresFor(simulated).contains(feature);
        }
        if (user.isAdmin()) {
            return true;
        }
        if (!monetizationProperties.isEnforce()) {
            return true;
        }
        return planFeatureRepository.existsByPlanAndFeatureKey(resolvePlan(user), feature);
    }

    public void requireFeatureAccess(User user, FeatureKey feature) {
        if (!canAccessFeature(user, feature)) {
            throw new FeatureLockedException(feature);
        }
    }

    /**
     * Resolve the chat tone the user is actually allowed to use. "plain" is always available;
     * "professional"/"genz" require {@link FeatureKey#CHAT_PERSONAS}. Falls back to "plain" rather
     * than failing, so a downgraded user still gets a reply. Respects the admin view-as simulation
     * and the {@code monetization.enforce} flag via {@link #canAccessFeature}.
     */
    @Transactional(readOnly = true)
    public String resolveChatMode(String mode, User user) {
        if (mode == null || mode.isBlank()) {
            return "plain";
        }
        String normalized = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("plain".equals(normalized)) {
            return "plain";
        }
        return canAccessFeature(user, FeatureKey.CHAT_PERSONAS) ? normalized : "plain";
    }

    @Transactional(readOnly = true)
    public Entitlements describe(User user) {
        boolean admin = user.isAdmin();
        PlanKey simulated = ViewAsContext.plan();
        if (simulated != null) {
            return new Entitlements(simulated, SubscriptionStatus.ACTIVE, featuresFor(simulated), admin);
        }
        if (admin) {
            return new Entitlements(PlanKey.PREMIUM, SubscriptionStatus.ACTIVE, EnumSet.allOf(FeatureKey.class), true);
        }
        SubscriptionPlan plan = resolvePlan(user);
        SubscriptionStatus status = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)
                .map(UserSubscription::getStatus)
                .orElse(SubscriptionStatus.INACTIVE);
        Set<FeatureKey> features = monetizationProperties.isEnforce()
                ? planFeatureRepository.findAllByPlan(plan).stream()
                        .map(PlanFeature::getFeatureKey)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(FeatureKey.class)))
                : EnumSet.allOf(FeatureKey.class);
        return new Entitlements(plan.getPlanKey(), status, features, false);
    }

    private Set<FeatureKey> featuresFor(PlanKey planKey) {
        SubscriptionPlan plan = planRepository.findByPlanKey(planKey)
                .orElseThrow(() -> new IllegalStateException(planKey + " plan is not seeded"));
        return planFeatureRepository.findAllByPlan(plan).stream()
                .map(PlanFeature::getFeatureKey)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FeatureKey.class)));
    }

    private SubscriptionPlan resolvePlan(User user) {
        Optional<UserSubscription> latest = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user);
        if (latest.isPresent() && grantsAccess(latest.get())) {
            return latest.get().getPlan();
        }
        return planRepository.findByPlanKey(PlanKey.FREE)
                .orElseThrow(() -> new IllegalStateException("FREE plan is not seeded"));
    }

    private boolean grantsAccess(UserSubscription subscription) {
        if (!subscription.getStatus().grantsAccess()) {
            return false;
        }
        LocalDateTime periodEnd = subscription.getCurrentPeriodEnd();
        return periodEnd == null || periodEnd.isAfter(LocalDateTime.now());
    }

    /** Snapshot of a user's plan, subscription status, granted features, and admin flag. */
    public record Entitlements(PlanKey plan, SubscriptionStatus status, Set<FeatureKey> features, boolean admin) {
    }
}
