package com.teng.app.gastosai;

import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.repository.PlanFeatureRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    @Mock MonetizationProperties monetizationProperties;
    @Mock SubscriptionPlanRepository planRepository;
    @Mock PlanFeatureRepository planFeatureRepository;
    @Mock UserSubscriptionRepository userSubscriptionRepository;
    @InjectMocks EntitlementService service;

    private final User user = User.builder().id(1L).email("u@b.com").name("U").password("x").build();

    private SubscriptionPlan plan(PlanKey key) {
        return SubscriptionPlan.builder().id(key == PlanKey.FREE ? 1L : 2L).planKey(key).name(key.name()).build();
    }

    @Test
    void betaModeUnlocksEverythingWithoutTouchingTheDatabase() {
        when(monetizationProperties.isEnforce()).thenReturn(false);

        assertThat(service.canAccessFeature(user, FeatureKey.AI_ANALYTICS)).isTrue();
        verifyNoInteractions(planRepository, planFeatureRepository, userSubscriptionRepository);
    }

    @Test
    void enforced_freePlanWithoutFeature_isBlocked() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan free = plan(PlanKey.FREE);
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(planRepository.findByPlanKey(PlanKey.FREE)).thenReturn(Optional.of(free));
        when(planFeatureRepository.existsByPlanAndFeatureKey(free, FeatureKey.AI_ANALYTICS)).thenReturn(false);

        assertThat(service.canAccessFeature(user, FeatureKey.AI_ANALYTICS)).isFalse();
    }

    @Test
    void enforced_activePremium_isAllowed() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan premium = plan(PlanKey.PREMIUM);
        UserSubscription sub = UserSubscription.builder()
                .plan(premium).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().plusDays(1)).build();
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(sub));
        when(planFeatureRepository.existsByPlanAndFeatureKey(premium, FeatureKey.AI_ANALYTICS)).thenReturn(true);

        assertThat(service.canAccessFeature(user, FeatureKey.AI_ANALYTICS)).isTrue();
    }

    @Test
    void enforced_expiredPremium_fallsBackToFree() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan premium = plan(PlanKey.PREMIUM);
        SubscriptionPlan free = plan(PlanKey.FREE);
        UserSubscription expired = UserSubscription.builder()
                .plan(premium).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().minusDays(1)).build();
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(expired));
        when(planRepository.findByPlanKey(PlanKey.FREE)).thenReturn(Optional.of(free));
        when(planFeatureRepository.existsByPlanAndFeatureKey(free, FeatureKey.AI_ANALYTICS)).thenReturn(false);

        assertThat(service.canAccessFeature(user, FeatureKey.AI_ANALYTICS)).isFalse();
    }

    @Test
    void resolveChatMode_plainAndBlank_returnPlain_withoutDbHit() {
        assertThat(service.resolveChatMode("plain", user)).isEqualTo("plain");
        assertThat(service.resolveChatMode(null, user)).isEqualTo("plain");
        assertThat(service.resolveChatMode("  ", user)).isEqualTo("plain");
        verifyNoInteractions(planRepository, planFeatureRepository, userSubscriptionRepository);
    }

    @Test
    void resolveChatMode_premiumTone_allowedWhenEntitled() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan premium = plan(PlanKey.PREMIUM);
        UserSubscription sub = UserSubscription.builder()
                .plan(premium).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().plusDays(1)).build();
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(sub));
        when(planFeatureRepository.existsByPlanAndFeatureKey(premium, FeatureKey.CHAT_PERSONAS)).thenReturn(true);

        assertThat(service.resolveChatMode("genz", user)).isEqualTo("genz");
    }

    @Test
    void resolveChatMode_premiumTone_fallsBackToPlainWhenNotEntitled() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan free = plan(PlanKey.FREE);
        when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(planRepository.findByPlanKey(PlanKey.FREE)).thenReturn(Optional.of(free));
        when(planFeatureRepository.existsByPlanAndFeatureKey(free, FeatureKey.CHAT_PERSONAS)).thenReturn(false);

        assertThat(service.resolveChatMode("professional", user)).isEqualTo("plain");
    }

    @Test
    void requireFeatureAccess_throwsWhenBlocked() {
        when(monetizationProperties.isEnforce()).thenReturn(true);
        SubscriptionPlan free = plan(PlanKey.FREE);
        lenient().when(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(planRepository.findByPlanKey(PlanKey.FREE)).thenReturn(Optional.of(free));
        when(planFeatureRepository.existsByPlanAndFeatureKey(free, FeatureKey.BUDGET_FORECASTING)).thenReturn(false);

        assertThatThrownBy(() -> service.requireFeatureAccess(user, FeatureKey.BUDGET_FORECASTING))
                .isInstanceOf(FeatureLockedException.class);
    }
}
