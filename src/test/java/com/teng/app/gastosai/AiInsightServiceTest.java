package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.MonthlyComparisonResponse;
import com.teng.app.gastosai.dto.TopCategoryInsightResponse;
import com.teng.app.gastosai.dto.MonthSummaryInsightResponse;
import com.teng.app.gastosai.dto.RecommendationsInsightResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiInsightService;
import com.teng.app.gastosai.service.AiUsageService;
import com.teng.app.gastosai.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiInsightServiceTest {

    @Mock
    ExpenseService expenseService;

    @Mock
    SqlGenerator sqlGenerator;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AiUsageService aiUsageService;

    @Mock
    AiProviderProperties aiProviderProperties;

    @Mock
    OpenAiProperties openAiProperties;

    @Mock
    ClaudeProperties claudeProperties;

    @InjectMocks
    AiInsightService aiInsightService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
        org.mockito.Mockito.lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
    }

    private User user() {
        return User.builder().name("Test").email("t@t.com").password("x").build();
    }

    @Test
    void getTopCategory_returnsHighestTotalCategory() {
        User user = user();
        when(expenseService.categoryReportForMonth(user, "2026-06")).thenReturn(List.of(
                new CategoryReportItem("Food", new BigDecimal("1200.00")),
                new CategoryReportItem("Transport", new BigDecimal("500.00")),
                new CategoryReportItem("Entertainment", new BigDecimal("800.00"))
        ));
        when(expenseService.monthlyComparison(user, "2026-06")).thenReturn(
                new MonthlyComparisonResponse("2026-06", new BigDecimal("2500.00"), new BigDecimal("2000.00"), new BigDecimal("25.00"))
        );

        TopCategoryInsightResponse result = aiInsightService.getTopCategory(user, "2026-06");

        assertThat(result.category()).isEqualTo("Food");
        assertThat(result.total()).isEqualByComparingTo("1200.00");
        assertThat(result.percentOfMonthTotal()).isEqualByComparingTo("48.00");
    }

    @Test
    void getMonthSummary_passesContextJsonToSqlGenerator() throws Exception {
        User user = user();
        when(expenseService.categoryReportForMonth(user, "2026-06")).thenReturn(List.of(
                new CategoryReportItem("Food", new BigDecimal("1200.00"))
        ));
        when(expenseService.monthlyComparison(user, "2026-06")).thenReturn(
                new MonthlyComparisonResponse("2026-06", new BigDecimal("1200.00"), new BigDecimal("1000.00"), new BigDecimal("20.00"))
        );
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        when(sqlGenerator.generateInsightSummary(contextCaptor.capture(), eq("month-summary"), eq("plain")))
                .thenReturn("You spent ₱1200 in June.");

        MonthSummaryInsightResponse result = aiInsightService.getMonthSummary(user, "2026-06");

        assertThat(result.summary()).isEqualTo("You spent ₱1200 in June.");
        assertThat(contextCaptor.getValue()).contains("2026-06");
        assertThat(contextCaptor.getValue()).contains("topCategories");
    }

    @Test
    void getRecommendations_parsesJsonArrayIntoList() throws Exception {
        User user = user();
        when(expenseService.categoryReportForMonth(user, "2026-06")).thenReturn(List.of(
                new CategoryReportItem("Food", new BigDecimal("1200.00"))
        ));
        when(expenseService.monthlyComparison(user, "2026-06")).thenReturn(
                new MonthlyComparisonResponse("2026-06", new BigDecimal("1200.00"), new BigDecimal("1000.00"), new BigDecimal("20.00"))
        );
        when(sqlGenerator.generateInsightSummary(any(), eq("recommendations"), eq("plain")))
                .thenReturn("[\"Reduce Food spending.\",\"Set a budget for Transport.\"]");

        RecommendationsInsightResponse result = aiInsightService.getRecommendations(user, "2026-06");

        assertThat(result.recommendations()).hasSize(2);
        assertThat(result.recommendations().get(0)).isEqualTo("Reduce Food spending.");
    }
}
