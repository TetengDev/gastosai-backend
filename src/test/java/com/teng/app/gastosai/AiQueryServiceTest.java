package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQueryServiceTest {

    @Mock SqlGenerator sqlGenerator;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks AiQueryService aiQueryService;

    private User user(boolean admin) {
        return User.builder()
                .id(admin ? null : 42L)
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
    void query_admin_noUserFilter() {
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
    void query_singleScalarRow_returnsFormattedValue() {
        when(sqlGenerator.generateSql(anyString())).thenReturn("SELECT SUM(amount) FROM expenses");
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(Map.of("sum", new BigDecimal("123.456"))));
        when(sqlGenerator.generateSummary(any(), any(), any())).thenReturn("123.46");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("total", "plain", user(true));
        assertThat(r.answer()).isNotNull();
    }
}
