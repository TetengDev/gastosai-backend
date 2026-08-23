package com.teng.app.gastosai;

import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.repository.PlanFeatureRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.EntitlementService;
import com.teng.app.gastosai.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock SubscriptionPlanRepository planRepository;
    @Mock UserSubscriptionRepository userSubscriptionRepository;
    @InjectMocks SubscriptionService service;

    private final User user = User.builder().id(1L).email("new@b.com").name("New").password("x").build();

    private SubscriptionPlan plan(PlanKey key) {
        return SubscriptionPlan.builder().id(key == PlanKey.FREE ? 1L : 2L).planKey(key).name(key.name()).build();
    }

    @Test
    void startTrial_enrolsNewUserIntoATrialWithAnExpiry() {
        SubscriptionPlan trialPlan = plan(PlanKey.TRIAL);
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(planRepository.findByPlanKey(PlanKey.TRIAL)).thenReturn(Optional.of(trialPlan));
        when(userSubscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        UserSubscription trial = service.startTrial(user).orElseThrow();

        assertThat(trial.getUser()).isSameAs(user);
        assertThat(trial.getPlan().getPlanKey()).isEqualTo(PlanKey.TRIAL);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.getStartedAt()).isNotNull();
        assertThat(trial.getCurrentPeriodEnd())
                .isNotNull()
                .isAfter(before.plusDays(SubscriptionService.TRIAL_DAYS).minusMinutes(1))
                .isBefore(LocalDateTime.now().plusDays(SubscriptionService.TRIAL_DAYS).plusMinutes(1));
    }

    @Test
    void startTrial_leavesAnExistingSubscriptionAlone() {
        // Seeded test users (and anyone already paying) must not be re-enrolled or downgraded.
        UserSubscription existing = UserSubscription.builder()
                .user(user).plan(plan(PlanKey.PREMIUM)).status(SubscriptionStatus.ACTIVE).build();
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(existing));

        assertThat(service.startTrial(user)).isEmpty();
        verify(userSubscriptionRepository, never()).save(any());
    }

    @Test
    void startTrial_withoutASeededTrialPlan_isANoOpRatherThanAFailure() {
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(planRepository.findByPlanKey(PlanKey.TRIAL)).thenReturn(Optional.empty());

        assertThat(service.startTrial(user)).isEmpty();
        verify(userSubscriptionRepository, never()).save(any());
    }

    /**
     * The trial has no expiry job: it lapses because {@link EntitlementService} stops honouring a
     * subscription whose period has ended. Asserted here, against a real EntitlementService, because
     * it is the half of the trial lifecycle that {@link SubscriptionService} deliberately does not own.
     */
    @Test
    void expiredTrial_degradesToFreeWithoutAnErrorState() {
        MonetizationProperties monetization = new MonetizationProperties();
        monetization.setEnforce(true);
        PlanFeatureRepository planFeatureRepository = org.mockito.Mockito.mock(PlanFeatureRepository.class);
        EntitlementService entitlements = new EntitlementService(
                monetization, planRepository, planFeatureRepository, userSubscriptionRepository);

        SubscriptionPlan freePlan = plan(PlanKey.FREE);
        UserSubscription lapsed = UserSubscription.builder()
                .user(user)
                .plan(plan(PlanKey.TRIAL))
                .status(SubscriptionStatus.TRIAL)
                .startedAt(LocalDateTime.now().minusDays(SubscriptionService.TRIAL_DAYS + 1))
                .currentPeriodEnd(LocalDateTime.now().minusMinutes(1))
                .build();
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(lapsed));
        when(planRepository.findByPlanKey(PlanKey.FREE)).thenReturn(Optional.of(freePlan));
        when(planFeatureRepository.findAllByPlan(freePlan)).thenReturn(List.of());
        when(planFeatureRepository.existsByPlanAndFeatureKey(freePlan, FeatureKey.AI_ANALYTICS)).thenReturn(false);

        assertThat(entitlements.describe(user).plan()).isEqualTo(PlanKey.FREE);
        assertThat(entitlements.canAccessFeature(user, FeatureKey.AI_ANALYTICS)).isFalse();
    }
}
