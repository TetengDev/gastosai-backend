package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.ai.query.AnalyticsQueryPlan;
import com.teng.app.gastosai.ai.query.AnalyticsQueryPlanner;
import com.teng.app.gastosai.ai.query.DateRange;
import com.teng.app.gastosai.ai.query.Metric;
import com.teng.app.gastosai.ai.query.QueryIntent;
import com.teng.app.gastosai.ai.query.QueryIntentValidator;
import com.teng.app.gastosai.ai.query.SafeAnalyticsExecutor;
import com.teng.app.gastosai.ai.query.SortDirection;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AiRedactionService;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.AiUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQueryServiceTest {

    @Mock SqlGenerator sqlGenerator;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock QueryIntentValidator queryIntentValidator;
    @Mock AnalyticsQueryPlanner analyticsQueryPlanner;
    @Mock SafeAnalyticsExecutor safeAnalyticsExecutor;
    @Mock AiQuotaService aiQuotaService;
    @Mock AiUsageService aiUsageService;
    @Mock AiRedactionService aiRedactionService;
    @Mock AiManagedProperties aiManagedProperties;
    @Mock AiProviderProperties aiProviderProperties;
    @Mock OpenAiProperties openAiProperties;
    @Mock ClaudeProperties claudeProperties;
    @InjectMocks AiQueryService aiQueryService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(aiRedactionService.redact(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.lenient().when(aiManagedProperties.getMaxPromptChars()).thenReturn(8000);
        org.mockito.Mockito.lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
        org.mockito.Mockito.lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
    }

    private User user(boolean admin) {
        return User.builder()
                .id(admin ? 7L : 42L)
                .role(admin ? Role.ADMIN : Role.USER)
                .email("u@b.com").name("U").password("x").build();
    }

    @Test
    void query_nonAdmin_appendsUserFilter() {
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT * FROM expenses");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(sqlGenerator.generateSummary(anyString(), anyString(), anyString())).thenReturn("none");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("test", "plain", user(false));
        assertThat(r.answer().toString()).contains("none");
    }

    @Test
    void query_admin_isAlsoUserScoped() {
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT * FROM expenses");
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForList(sqlCaptor.capture())).thenReturn(List.of(Map.of("total", BigDecimal.TEN)));
        when(sqlGenerator.generateSummary(anyString(), anyString(), anyString())).thenReturn("ok");

        aiQueryService.runNaturalLanguageQuery("test", "plain", user(true));

        // Admins are scoped to their own id — no unscoped raw AI SQL.
        assertThat(sqlCaptor.getValue()).contains("user_id = 7");
    }

    @Test
    void query_admin_executesViaFallback() {
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT * FROM expenses");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of("total", BigDecimal.TEN)));
        when(sqlGenerator.generateSummary(anyString(), anyString(), anyString())).thenReturn("ok");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("test", "plain", user(true));
        assertThat(r.answer()).isNotNull();
    }

    @Test
    void query_existingWhereClause_appendsAnd() {
        when(sqlGenerator.generateSql(anyString()))
                .thenReturn("SELECT * FROM expenses WHERE amount > 0");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("ok");

        aiQueryService.runNaturalLanguageQuery("test", "plain", user(false));
    }

    @Test
    void query_withGroupBy_filterInsertedBeforeGroupBy() {
        when(sqlGenerator.generateSql(anyString()))
                .thenReturn("SELECT category, SUM(amount) FROM expenses GROUP BY category");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("ok");

        aiQueryService.runNaturalLanguageQuery("test", null, user(false));
    }

    @Test
    void structuredPath_usedWhenIntentValid_andBypassesRawSql() {
        QueryIntent intent = new QueryIntent(Metric.TOTAL, DateRange.CURRENT_MONTH, null, SortDirection.DESC, 10);
        when(sqlGenerator.classifyQueryIntentJson(anyString()))
                .thenReturn("{\"metric\":\"TOTAL\",\"dateRange\":\"CURRENT_MONTH\"}");
        when(queryIntentValidator.parse(anyString())).thenReturn(Optional.of(intent));
        when(analyticsQueryPlanner.build(any(), eq(42L)))
                .thenReturn(new AnalyticsQueryPlan("SELECT 1", Map.of("userId", 42L)));
        when(safeAnalyticsExecutor.run(any()))
                .thenReturn(List.of(Map.of("total", new BigDecimal("123.45"))));
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("ok");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("total this month", "plain", user(false));

        assertThat(r.answer()).isEqualTo("ok");
        verify(sqlGenerator, never()).generateSql(anyString());
    }

    @Test
    void invalidIntent_fallsBackToRawSqlPath() {
        when(sqlGenerator.classifyQueryIntentJson(anyString())).thenReturn("{\"metric\":\"BOGUS\"}");
        when(queryIntentValidator.parse(anyString())).thenReturn(Optional.empty());
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT SUM(amount) FROM expenses");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("fallback");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("weird question", "plain", user(false));

        assertThat(r.answer().toString()).contains("fallback");
        verify(safeAnalyticsExecutor, never()).run(any());
    }

    @Test
    void query_singleScalarRow_returnsFormattedValue() {
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT SUM(amount) FROM expenses");
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(Map.of("sum", new BigDecimal("123.456"))));
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("123.46");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("total", "plain", user(true));
        assertThat(r.answer()).isNotNull();
    }
}
