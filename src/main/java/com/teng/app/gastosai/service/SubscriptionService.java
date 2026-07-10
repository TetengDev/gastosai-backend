package com.teng.app.gastosai.service;

import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
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

    private final SubscriptionPlanRepository planRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

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
