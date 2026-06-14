package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ClaudeSqlGenerator;
import com.teng.app.gastosai.config.ClaudeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeSqlGeneratorTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient claudeRestClient;

    @Mock
    ClaudeProperties claudeProperties;

    @InjectMocks
    ClaudeSqlGenerator generator;

    // Claude response format: {"content":[{"text":"..."}]}
    private String claudeResponse(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"}]}";
    }

    // ── generateSql — API key guard ───────────────────────────────────────────

    @Test
    void generateSql_nullApiKey_throws() {
        when(claudeProperties.getApiKey()).thenReturn(null);

        assertThatThrownBy(() -> generator.generateSql("how much did I spend?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAUDE_API_KEY");
    }

    @Test
    void generateSql_blankApiKey_throws() {
        when(claudeProperties.getApiKey()).thenReturn("  ");

        assertThatThrownBy(() -> generator.generateSql("how much did I spend?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAUDE_API_KEY");
    }

    // ── generateSql — HTTP path ───────────────────────────────────────────────

    @Test
    void generateSql_plainSqlResponse_returned() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("SELECT * FROM expenses WHERE user_id = 1"));

        String result = generator.generateSql("show all my expenses");

        assertThat(result).isEqualTo("SELECT * FROM expenses WHERE user_id = 1");
    }

    @Test
    void generateSql_fencedSqlResponse_extractsSql() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"```sql\\nSELECT id FROM expenses\\n```\"}]}";
        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(body);

        String result = generator.generateSql("list all expenses");

        assertThat(result).isEqualTo("SELECT id FROM expenses");
    }

    @Test
    void generateSql_emptyResponse_throws() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn("");

        assertThatThrownBy(() -> generator.generateSql("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void generateSql_nullResponse_throws() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(null);

        assertThatThrownBy(() -> generator.generateSql("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    // ── generateSummary — mode routing ───────────────────────────────────────

    @Test
    void generateSummary_plainMode_returnsContent() {
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("You spent ₱5,000 this month."));

        String result = generator.generateSummary("how much?", "[]", "plain");

        assertThat(result).isEqualTo("You spent ₱5,000 this month.");
    }

    @Test
    void generateSummary_professionalMode_returnsContent() {
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("Your total expenditure was ₱5,000."));

        String result = generator.generateSummary("how much?", "[]", "professional");

        assertThat(result).isEqualTo("Your total expenditure was ₱5,000.");
    }

    @Test
    void generateSummary_genzMode_returnsContent() {
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("bestie you spent ₱5k no cap"));

        String result = generator.generateSummary("how much?", "[]", "genz");

        assertThat(result).isEqualTo("bestie you spent ₱5k no cap");
    }

    // ── generateInsightSummary ────────────────────────────────────────────────

    @Test
    void generateInsightSummary_summaryType_returnsContent() {
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("You spent most on Food this month."));

        String result = generator.generateInsightSummary("{}", "summary", "plain");

        assertThat(result).isEqualTo("You spent most on Food this month.");
    }

    @Test
    void generateInsightSummary_recommendationsType_returnsContent() {
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeResponse("Reduce Food spending this month."));

        String result = generator.generateInsightSummary("{}", "recommendations", "plain");

        assertThat(result).contains("Reduce Food spending");
    }
}
