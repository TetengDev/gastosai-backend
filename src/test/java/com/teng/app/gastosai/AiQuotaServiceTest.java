package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AppEventService;
import com.teng.app.gastosai.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock AiUsageRepository aiUsageRepository;
    @Mock EntitlementService entitlementService;
    @Mock AppEventService appEventService;

    AiManagedProperties managedProps;
    AiQuotaService aiQuotaService;

    @BeforeEach
    void setUp() {
        managedProps = new AiManagedProperties();
        aiQuotaService = new AiQuotaService(managedProps, aiUsageRepository, entitlementService, appEventService);
    }

    private User user(boolean admin) {
        return User.builder().id(1L).email("u@t.com").name("U").password("x")
                .role(admin ? Role.ADMIN : Role.USER).build();
    }

    private EntitlementService.Entitlements entitlementsFor(PlanKey plan, boolean admin) {
        return new EntitlementService.Entitlements(plan, SubscriptionStatus.ACTIVE, EnumSet.noneOf(FeatureKey.class), admin);
    }

    /** Successful quota-bearing requests — what the per-plan quotas meter. */
    private void stubUsed(long count) {
        when(aiUsageRepository.countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(
                eq(1L), eq(AiUsageStatus.SUCCESS), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(count);
    }

    /** Quota-bearing attempts, success or failure — what the absolute monthly cap counts. */
    private void stubAttempted(long count) {
        when(aiUsageRepository.countByUserIdAndFeatureInAndCreatedAtAfter(
                eq(1L), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(count);
    }

    @Test
    void absoluteCap_blocks_whenAtCap_byoMode() {
        managedProps.setAllowSharedKey(false);
        managedProps.setAbsoluteMonthlyCap(1000);
        User user = user(false);
        stubAttempted(1000L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void absoluteCap_passes_whenUnderCap_byoMode() {
        managedProps.setAllowSharedKey(false);
        managedProps.setAbsoluteMonthlyCap(1000);
        User user = user(false);
        stubAttempted(999L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void absoluteCap_notApplied_toNonQuotaBearingFeature() {
        managedProps.setAllowSharedKey(false);
        managedProps.setAbsoluteMonthlyCap(0);
        User user = user(false);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.EXPENSE_INSIGHT))
                .doesNotThrowAnyException();
    }

    @Test
    void managedOff_noEnforcement() {
        managedProps.setAllowSharedKey(false);
        managedProps.setAbsoluteMonthlyCap(1000);
        stubAttempted(0L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user(false), AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void admin_bypasses() {
        managedProps.setAllowSharedKey(true);
        User user = user(true);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.PREMIUM, true));
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void freeAtCap_throws() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(30L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void freeUnderCap_noThrow() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(29L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void premiumCapEnforced() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaPremium(300);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.PREMIUM, false));
        stubUsed(300L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void trialCapEnforced() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaTrial(50);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.TRIAL, false));
        stubUsed(50L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void visionUnderSubCap_noThrow() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        managedProps.setVisionFree(5);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(10L);
        when(aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                eq(1L), eq(AiFeature.RECEIPT_ANALYSIS), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(4L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .doesNotThrowAnyException();
    }

    @Test
    void visionSubCapEnforced() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        managedProps.setVisionFree(5);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(10L);
        when(aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                eq(1L), eq(AiFeature.RECEIPT_ANALYSIS), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(5L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    /**
     * The paid-for-scan half of TEN-409: failures are our problem, not the user's plan's, so the
     * per-plan monthly quota still counts successes only. 29 failed attempts on top of 29
     * successes do not cost a FREE user their 30th request.
     */
    @Test
    void planQuota_ignoresFailedAttempts() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(29L);
        stubAttempted(58L); // 29 succeeded, 29 failed — well under the 1000 absolute cap
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    /** The vision sub-cap is a per-plan quota too: a failed scan is not a scan the user received. */
    @Test
    void visionSubCap_ignoresFailedAttempts() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        managedProps.setVisionFree(5);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(4L);
        stubAttempted(40L);
        when(aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                eq(1L), eq(AiFeature.RECEIPT_ANALYSIS), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(4L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .doesNotThrowAnyException();
    }

    /**
     * The backend-guard half of TEN-409: a client whose requests all fail is eventually refused.
     * Every success count is zero here, so the only thing that can trip the cap is the failures.
     */
    @Test
    void absoluteCap_countsFailedAttempts_andEventuallyRefuses() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        managedProps.setAbsoluteMonthlyCap(1000);
        User user = user(false);
        // No success stub: the SUCCESS-filtered counts default to 0 for this user.
        stubAttempted(1000L); // 1000 failures, 0 successes
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .isInstanceOf(AiQuotaExceededException.class);
        // Refused before the per-plan path, so entitlements are never consulted.
        verifyNoInteractions(entitlementService);
    }

    /**
     * Walks the cap rather than asserting one boundary value: the same client, failing every time,
     * is admitted while under the cap and refused once its failures reach it.
     */
    @Test
    void repeatedFailures_admittedThenRefused() {
        managedProps.setAllowSharedKey(false); // BYO key: no per-plan quota in the way
        managedProps.setAbsoluteMonthlyCap(3);
        User user = user(false);
        when(aiUsageRepository.countByUserIdAndFeatureInAndCreatedAtAfter(
                eq(1L), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(0L, 1L, 2L, 3L);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    /** The global daily pool is a backend guard too — failures spend it. */
    @Test
    void globalDailyCap_countsFailedAttempts() {
        managedProps.setAllowSharedKey(true);
        managedProps.setGlobalDailyMax(2000);
        User user = user(false);
        when(aiUsageRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(2000L);

        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
        verify(appEventService).recordAbuseTrip(eq("AI_GLOBAL_CAP"), eq(1L), eq("/ai"), any());
    }

    /** globalDailyUsed() reads every row, not the SUCCESS-filtered count it used to read. */
    @Test
    void globalDailyUsed_readsAllStatuses() {
        when(aiUsageRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(7L);
        assertThat(aiQuotaService.globalDailyUsed()).isEqualTo(7L);
        verify(aiUsageRepository, never()).countByStatusAndCreatedAtAfter(any(), any(LocalDateTime.class));
    }

    /** usedThisMonth() stays SUCCESS-only — /ai/usage reports it to the user. */
    @Test
    void usedThisMonth_staysSuccessOnly() {
        stubUsed(12L);
        assertThat(aiQuotaService.usedThisMonth(1L)).isEqualTo(12L);
    }

    @Test
    void attemptedThisMonth_countsEveryStatus() {
        stubAttempted(42L);
        assertThat(aiQuotaService.attemptedThisMonth(1L)).isEqualTo(42L);
    }

    @Test
    void quotaCount_excludesInsightFeatures() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        stubUsed(0L);

        aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AiFeature>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(aiUsageRepository, atLeastOnce()).countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(
                eq(1L), eq(AiUsageStatus.SUCCESS), captor.capture(), any(LocalDateTime.class));
        Collection<AiFeature> counted = captor.getValue();
        assertThat(counted).contains(AiFeature.CHAT_CRUD_ASSISTANT, AiFeature.RECEIPT_ANALYSIS);
        assertThat(counted).doesNotContain(
                AiFeature.EXPENSE_INSIGHT, AiFeature.MONTHLY_SUMMARY, AiFeature.BUDGET_ADVICE);
    }
}
