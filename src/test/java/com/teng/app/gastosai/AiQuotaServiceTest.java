package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock AiUsageRepository aiUsageRepository;
    @Mock EntitlementService entitlementService;

    AiManagedProperties managedProps;
    AiQuotaService aiQuotaService;

    @BeforeEach
    void setUp() {
        managedProps = new AiManagedProperties();
        aiQuotaService = new AiQuotaService(managedProps, aiUsageRepository, entitlementService);
    }

    private User user(boolean admin) {
        return User.builder().id(1L).email("u@t.com").name("U").password("x")
                .role(admin ? Role.ADMIN : Role.USER).build();
    }

    private EntitlementService.Entitlements entitlementsFor(PlanKey plan, boolean admin) {
        return new EntitlementService.Entitlements(plan, SubscriptionStatus.ACTIVE, EnumSet.noneOf(com.teng.app.gastosai.entity.FeatureKey.class), admin);
    }

    @Test
    void managedOff_noEnforcement() {
        managedProps.setAllowSharedKey(false);
        User user = user(false);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
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
        when(aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(eq(1L), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(30L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void freeUnderCap_noThrow() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        when(aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(eq(1L), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(29L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void visionSubCapEnforced() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        managedProps.setVisionFree(5);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        when(aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(eq(1L), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(10L);
        when(aiUsageRepository.countByUserIdAndFeatureAndStatusAndCreatedAtAfter(
                eq(1L), eq(AiFeature.RECEIPT_ANALYSIS), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(5L);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void failedRowsDontCountAgainstQuota() {
        managedProps.setAllowSharedKey(true);
        managedProps.setQuotaFree(30);
        User user = user(false);
        when(entitlementService.describe(user)).thenReturn(entitlementsFor(PlanKey.FREE, false));
        // Only SUCCESS rows are counted; 30 FAILED rows should not trigger quota
        when(aiUsageRepository.countByUserIdAndStatusAndCreatedAtAfter(eq(1L), eq(AiUsageStatus.SUCCESS), any(LocalDateTime.class)))
                .thenReturn(0L);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }
}
