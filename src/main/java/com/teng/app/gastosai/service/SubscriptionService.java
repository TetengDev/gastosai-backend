package com.teng.app.gastosai.service;

import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Mutates subscription state independently of any payment provider. A future
 * {@link com.teng.app.gastosai.payment.PaymentProvider} webhook calls these methods; today they are
 * the seam used by tests and any manual/admin activation.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    /**
     * Length of the trial a new signup is enrolled into. Matches the seeded {@code trial@gastosai.dev}
     * account so the automatic trial and the test account describe the same product.
     */
    public static final int TRIAL_DAYS = 14;

    /** Recorded in {@code provider} so an automatic trial is distinguishable from a paid or seeded row. */
    static final String TRIAL_PROVIDER = "signup";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionPlanRepository planRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    /**
     * Enrol a freshly registered user into a time-boxed TRIAL. Idempotent and non-destructive: a user
     * who already has any subscription row — a seeded test account, a returning user, a paid plan — is
     * left untouched and {@link Optional#empty()} is returned.
     *
     * <p>Expiry needs no scheduled job: {@link EntitlementService} treats a subscription whose
     * {@code currentPeriodEnd} has passed as non-granting and falls back to FREE, so the trial lapses
     * into the free tier silently rather than into an error state.
     */
    @Transactional
    public Optional<UserSubscription> startTrial(User user) {
        if (userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user).isPresent()) {
            return Optional.empty();
        }
        Optional<SubscriptionPlan> plan = planRepository.findByPlanKey(PlanKey.TRIAL);
        if (plan.isEmpty()) {
            // EntitlementSeeder runs at @Order(0) on every startup, so this is a misconfiguration
            // rather than a user error — signing up must still succeed, on the FREE tier.
            log.warn("Skipping trial enrolment for {}: TRIAL plan is not seeded", user.getEmail());
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        UserSubscription trial = userSubscriptionRepository.save(UserSubscription.builder()
                .user(user)
                .plan(plan.get())
                .status(SubscriptionStatus.TRIAL)
                .startedAt(now)
                .currentPeriodEnd(now.plusDays(TRIAL_DAYS))
                .provider(TRIAL_PROVIDER)
                .build());
        log.info("Enrolled {} into a {}-day trial ending {}", user.getEmail(), TRIAL_DAYS, trial.getCurrentPeriodEnd());
        return Optional.of(trial);
    }

    @Transactional
    public UserSubscription activate(User user, PlanKey planKey, String provider, String providerRef, LocalDateTime currentPeriodEnd) {
        SubscriptionPlan plan = planRepository.findByPlanKey(planKey)
                .orElseThrow(() -> new IllegalStateException("Plan not seeded: " + planKey));
        UserSubscription subscription = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)
                .orElseGet(UserSubscription::new);
        subscription.setUser(user);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartedAt(LocalDateTime.now());
        subscription.setCurrentPeriodEnd(currentPeriodEnd);
        subscription.setProvider(provider);
        subscription.setProviderRef(providerRef);
        return userSubscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public Optional<UserSubscription> findCurrentSubscription(User user) {
        return userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user);
    }

    @Transactional
    public void cancel(User user) {
        userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user).ifPresent(subscription -> {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            userSubscriptionRepository.save(subscription);
        });
    }
}
