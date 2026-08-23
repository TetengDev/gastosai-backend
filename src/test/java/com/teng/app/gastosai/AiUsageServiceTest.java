package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiCostProperties;
import com.teng.app.gastosai.dto.AiCostByPlanItem;
import com.teng.app.gastosai.dto.AiCostReport;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.service.AiUsageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiUsageServiceTest {

    private static final LocalDate CHECKED_ON = LocalDate.of(2026, 8, 24);
    private static final String SOURCE = "OpenAI API pricing page";

    @Mock AiUsageRepository aiUsageRepository;
    @Mock EntityManager entityManager;

    AiUsageService aiUsageService;

    @BeforeEach
    void setUp() {
        aiUsageService = serviceWith(new AiCostProperties());
    }

    private AiUsageService serviceWith(AiCostProperties costProperties) {
        return new AiUsageService(aiUsageRepository, costProperties, entityManager,
                2.50, 10.00, CHECKED_ON, SOURCE);
    }

    @Test
    void recordsSuccessEntry() {
        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "openai", "gpt-4o-mini", AiFeature.CHAT_CRUD_ASSISTANT,
                100, 50, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        AiUsage saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo(AiUsageStatus.SUCCESS);
        assertThat(saved.getInputTokens()).isEqualTo(100);
        assertThat(saved.getOutputTokens()).isEqualTo(50);
        assertThat(saved.getTotalTokens()).isEqualTo(150);
        assertThat(saved.getErrorCode()).isNull();
    }

    @Test
    void recordsFailedEntry() {
        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(2L, "openai", "gpt-4o-mini", AiFeature.RECEIPT_ANALYSIS,
                null, null, AiUsageStatus.FAILED, "IOException");

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        AiUsage saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AiUsageStatus.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("IOException");
        assertThat(saved.getEstimatedCostUsd()).isNull();
    }

    @Test
    void estimatesCostWhenTokensKnown() {
        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "openai", "gpt-4o-mini", AiFeature.MONTHLY_SUMMARY,
                1_000_000, 1_000_000, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        BigDecimal cost = captor.getValue().getEstimatedCostUsd();
        assertThat(cost).isNotNull();
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.750000"));
    }

    @Test
    void nullTokensYieldNullCost() {
        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "claude", "claude-3-5-sonnet", AiFeature.CHAT_CONTEXT_RESOLUTION,
                null, null, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        assertThat(captor.getValue().getEstimatedCostUsd()).isNull();
    }

    @Test
    void customCostRatesApplied() {
        AiCostProperties custom = new AiCostProperties();
        custom.setInputPerMtokUsd(0.30);
        custom.setOutputPerMtokUsd(1.20);
        AiUsageService svc = serviceWith(custom);

        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.record(1L, "openai", "gpt-4o", AiFeature.CHAT_CRUD_ASSISTANT,
                1_000_000, 1_000_000, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        assertThat(captor.getValue().getEstimatedCostUsd())
                .isEqualByComparingTo(new BigDecimal("1.500000"));
    }

    @Test
    void receiptAnalysisIsPricedAtTheVisionRate() {
        when(aiUsageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "openai", "gpt-4o", AiFeature.RECEIPT_ANALYSIS,
                1_000_000, 1_000_000, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        // 2.50 + 10.00, an order of magnitude above the 0.75 the same tokens cost as text.
        assertThat(captor.getValue().getEstimatedCostUsd())
                .isEqualByComparingTo(new BigDecimal("12.500000"));
    }

    @Test
    void costReportStatesThePricesItUsedAndWhenTheyWereChecked() {
        stubUsageAndPlans();

        AiCostReport report = aiUsageService.costReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 24));

        assertThat(report.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(report.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(report.pricing().textInputPerMtokUsd()).isEqualByComparingTo("0.15");
        assertThat(report.pricing().textOutputPerMtokUsd()).isEqualByComparingTo("0.60");
        assertThat(report.pricing().visionInputPerMtokUsd()).isEqualByComparingTo("2.50");
        assertThat(report.pricing().visionOutputPerMtokUsd()).isEqualByComparingTo("10.00");
        assertThat(report.pricing().pricesLastCheckedOn()).isEqualTo(CHECKED_ON);
        assertThat(report.pricing().pricesSource()).isEqualTo(SOURCE);
    }

    @Test
    void costReportSeparatesTextFromVisionPerUser() {
        stubUsageAndPlans();

        AiCostReport report = aiUsageService.costReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 24));

        var premiumUser = report.byUser().stream().filter(u -> u.userId() == 1L).findFirst().orElseThrow();
        assertThat(premiumUser.plan()).isEqualTo("PREMIUM");
        // 1_000_000 in / 500_000 out of text: 0.15 + 0.30
        assertThat(premiumUser.text().requests()).isEqualTo(10);
        assertThat(premiumUser.text().inputTokens()).isEqualTo(1_000_000);
        assertThat(premiumUser.text().costUsd()).isEqualByComparingTo("0.450000");
        // 200_000 in / 10_000 out of vision: 0.50 + 0.10
        assertThat(premiumUser.vision().requests()).isEqualTo(2);
        assertThat(premiumUser.vision().costUsd()).isEqualByComparingTo("0.600000");
        assertThat(premiumUser.totalCostUsd()).isEqualByComparingTo("1.050000");

        // Two vision calls out-cost ten text calls — the split is the point of the report.
        assertThat(premiumUser.vision().costUsd()).isGreaterThan(premiumUser.text().costUsd());
    }

    @Test
    void costReportFoldsTextAndVisionRowsOfOneUserTogether() {
        stubUsageAndPlans();

        AiCostReport report = aiUsageService.costReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 24));

        // Two text features for user 1 (CHAT_CRUD_ASSISTANT + MONTHLY_SUMMARY) land in one slice.
        assertThat(report.byUser()).hasSize(2);
        assertThat(report.byUser().getFirst().userId()).isEqualTo(1L);
        assertThat(report.byUser().getFirst().totalCostUsd()).isEqualByComparingTo("1.050000");
    }

    @Test
    void costReportAggregatesPerPlanAndDividesByActiveUsers() {
        stubUsageAndPlans();

        AiCostReport report = aiUsageService.costReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 24));

        assertThat(report.byPlan()).extracting(AiCostByPlanItem::plan)
                .containsExactly("FREE", "PREMIUM");

        AiCostByPlanItem premium = report.byPlan().stream()
                .filter(p -> p.plan().equals("PREMIUM")).findFirst().orElseThrow();
        assertThat(premium.activeUsers()).isEqualTo(1);
        assertThat(premium.totalCostUsd()).isEqualByComparingTo("1.050000");
        assertThat(premium.costPerActiveUserUsd()).isEqualByComparingTo("1.050000");

        AiCostByPlanItem free = report.byPlan().stream()
                .filter(p -> p.plan().equals("FREE")).findFirst().orElseThrow();
        // User 2 has no subscription row at all, so it reads as FREE.
        assertThat(free.activeUsers()).isEqualTo(1);
        assertThat(free.vision().requests()).isZero();
        assertThat(free.text().costUsd()).isEqualByComparingTo("0.045000");
        assertThat(free.costPerActiveUserUsd()).isEqualByComparingTo("0.045000");
    }

    @Test
    void costReportDefaultsToMonthToDateInManila() {
        stubUsageAndPlans();

        AiCostReport report = aiUsageService.costReport(null, null);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Manila"));
        assertThat(report.periodStart()).isEqualTo(today.withDayOfMonth(1));
        assertThat(report.periodEnd()).isEqualTo(today);
    }

    @Test
    void costReportRejectsAnInvertedPeriod() {
        assertThatThrownBy(() -> aiUsageService.costReport(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be before");
    }

    @Test
    void costReportOnNoUsageIsEmptyButStillStatesItsPrices() {
        stubQueries(List.of(), List.of());

        AiCostReport report = aiUsageService.costReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 24));

        assertThat(report.byUser()).isEmpty();
        assertThat(report.byPlan()).isEmpty();
        assertThat(report.pricing().pricesLastCheckedOn()).isEqualTo(CHECKED_ON);
    }

    /**
     * User 1 (PREMIUM): ten text calls and two receipt scans. User 2 (no subscription row, so it
     * reads as FREE): text only.
     */
    private void stubUsageAndPlans() {
        stubQueries(
                List.of(
                        new Object[]{1L, AiFeature.CHAT_CRUD_ASSISTANT, 8L, 900_000L, 450_000L},
                        new Object[]{1L, AiFeature.MONTHLY_SUMMARY, 2L, 100_000L, 50_000L},
                        new Object[]{1L, AiFeature.RECEIPT_ANALYSIS, 2L, 200_000L, 10_000L},
                        new Object[]{2L, AiFeature.CHAT_CRUD_ASSISTANT, 3L, 100_000L, 50_000L}),
                List.<Object[]>of(new Object[]{1L, PlanKey.PREMIUM}));
    }

    /** Routes the service's two JPQL queries to their own result lists. */
    private void stubQueries(List<Object[]> usageRows, List<Object[]> planRows) {
        TypedQuery<Object[]> usageQuery = queryReturning(usageRows);
        TypedQuery<Object[]> planQuery = queryReturning(planRows);
        when(entityManager.createQuery(anyString(), eq(Object[].class))).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("FROM UserSubscription") ? planQuery : usageQuery);
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> queryReturning(List<Object[]> rows) {
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return query;
    }
}
