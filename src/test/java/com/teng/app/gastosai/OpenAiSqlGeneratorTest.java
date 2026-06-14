package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.OpenAiSqlGenerator;
import com.teng.app.gastosai.config.OpenAiProperties;
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
class OpenAiSqlGeneratorTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient openAiRestClient;

    @Mock
    OpenAiProperties openAiProperties;

    @InjectMocks
    OpenAiSqlGenerator generator;

    // ── generateSql — SQL fence extraction via HTTP ──────────────────────────

    @Test
    void generateSql_fencedSqlResponse_extractsSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"```sql\\nSELECT id FROM expenses\\n```"}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSql("list all expenses");

        assertThat(result).isEqualTo("SELECT id FROM expenses");
    }

    @Test
    void generateSql_genericFencedResponse_extractsSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"```\\nSELECT amount FROM expenses\\n```"}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSql("list amounts");

        assertThat(result).isEqualTo("SELECT amount FROM expenses");
    }

    // ── generateSql — missing API key ─────────────────────────────────────────

    @Test
    void generateSql_missingApiKey_throws() {
        when(openAiProperties.getApiKey()).thenReturn(null);

        assertThatThrownBy(() -> generator.generateSql("how much did I spend?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void generateSql_blankApiKey_throws() {
        when(openAiProperties.getApiKey()).thenReturn("  ");

        assertThatThrownBy(() -> generator.generateSql("how much did I spend?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    // ── generateSql — HTTP path ───────────────────────────────────────────────

    @Test
    void generateSql_validResponse_returnsExtractedSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"SELECT * FROM expenses WHERE user_id = 1"}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSql("show all expenses");

        assertThat(result).isEqualTo("SELECT * FROM expenses WHERE user_id = 1");
    }

    @Test
    void generateSql_emptyResponse_throws() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
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
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
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
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"You spent ₱5,000 this month."}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSummary("how much?", "[]", "plain");

        assertThat(result).isEqualTo("You spent ₱5,000 this month.");
    }

    @Test
    void generateSummary_professionalMode_returnsContent() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"Your total expenditure was ₱5,000."}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSummary("how much?", "[]", "professional");

        assertThat(result).isEqualTo("Your total expenditure was ₱5,000.");
    }

    @Test
    void generateSummary_genzMode_returnsContent() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"bestie you spent ₱5k no cap 💸"}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateSummary("how much?", "[]", "genz");

        assertThat(result).isEqualTo("bestie you spent ₱5k no cap 💸");
    }

    // ── generateInsightSummary ────────────────────────────────────────────────

    @Test
    void generateInsightSummary_summaryType_returnsSummary() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"You spent most on Food this month."}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateInsightSummary("{}", "summary", "plain");

        assertThat(result).isEqualTo("You spent most on Food this month.");
    }

    @Test
    void generateInsightSummary_recommendationsType_usesRecommendationsPrompt() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String apiResponse = """
                {"choices":[{"message":{"content":"[\\"Reduce Food spending.\\"]"}}]}
                """;
        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(apiResponse);

        String result = generator.generateInsightSummary("{}", "recommendations", "plain");

        assertThat(result).contains("Reduce Food spending");
    }
}
