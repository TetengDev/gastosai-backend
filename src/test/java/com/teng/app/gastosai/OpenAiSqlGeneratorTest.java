package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
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

    private String apiResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
    }

    private String apiResponseNoUsage(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void generateSql_fencedSqlResponse_extractsSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"content\":\"```sql\\nSELECT id FROM expenses\\n```\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<String> result = generator.generateSql("list all expenses");

        assertThat(result.value()).isEqualTo("SELECT id FROM expenses");
        assertThat(result.usage().inputTokens()).isEqualTo(80);
        assertThat(result.usage().outputTokens()).isEqualTo(40);
    }

    @Test
    void generateSql_usageAbsentWhenMissing() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"content\":\"SELECT id FROM expenses\"}}]}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<String> result = generator.generateSql("list all expenses");

        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().outputTokens()).isNull();
    }

    @Test
    void generateSql_genericFencedResponse_extractsSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"content\":\"```\\nSELECT amount FROM expenses\\n```\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<String> result = generator.generateSql("list amounts");

        assertThat(result.value()).isEqualTo("SELECT amount FROM expenses");
    }

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

    @Test
    void generateSql_validResponse_returnsExtractedSql() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"content\":\"SELECT * FROM expenses WHERE user_id = 1\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<String> result = generator.generateSql("show all expenses");

        assertThat(result.value()).isEqualTo("SELECT * FROM expenses WHERE user_id = 1");
    }

    @Test
    void generateSql_emptyResponse_throws() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn("");

        assertThatThrownBy(() -> generator.generateSql("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void generateSql_nullResponse_throws() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(null);

        assertThatThrownBy(() -> generator.generateSql("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void generateSummary_plainMode_returnsContent() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(apiResponse("You spent ₱5,000 this month."));

        LlmResult<String> result = generator.generateSummary("how much?", "[]", "plain");

        assertThat(result.value()).isEqualTo("You spent ₱5,000 this month.");
    }

    @Test
    void generateSummary_professionalMode_returnsContent() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(apiResponse("Your total expenditure was ₱5,000."));

        LlmResult<String> result = generator.generateSummary("how much?", "[]", "professional");

        assertThat(result.value()).isEqualTo("Your total expenditure was ₱5,000.");
    }

    @Test
    void generateSummary_genzMode_returnsContent() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(apiResponse("bestie you spent ₱5k no cap"));

        LlmResult<String> result = generator.generateSummary("how much?", "[]", "genz");

        assertThat(result.value()).isEqualTo("bestie you spent ₱5k no cap");
    }

    @Test
    void generateInsightSummary_summaryType_returnsSummary() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(apiResponse("You spent most on Food this month."));

        LlmResult<String> result = generator.generateInsightSummary("{}", "summary", "plain");

        assertThat(result.value()).isEqualTo("You spent most on Food this month.");
    }

    @Test
    void generateInsightSummary_recommendationsType_usesRecommendationsPrompt() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"content\":\"[\\\"Reduce Food spending.\\\"]\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<String> result = generator.generateInsightSummary("{}", "recommendations", "plain");

        assertThat(result.value()).contains("Reduce Food spending");
    }

    @Test
    void classifyIntent_toolCallResponse_returnsToolCall() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"create_expense\",\"arguments\":\"{\\\"amount\\\":500,\\\"description\\\":\\\"Lunch\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<ChatToolCall> result = generator.classifyIntent("Add lunch for 500");

        assertThat(result.value().toolName()).isEqualTo("create_expense");
        assertThat(result.value().paramsJson()).contains("amount");
        assertThat(result.value().paramsJson()).contains("500");
        assertThat(result.usage().inputTokens()).isEqualTo(80);
    }

    @Test
    void classifyIntent_textResponse_returnsTextToolCall() {
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String raw = "{\"choices\":[{\"message\":{\"tool_calls\":[],\"content\":\"I cannot do that.\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":40}}";
        when(openAiRestClient.post().uri(anyString()).contentType(any()).body(anyString()).retrieve().body(String.class))
                .thenReturn(raw);

        LlmResult<ChatToolCall> result = generator.classifyIntent("Tell me a joke");

        assertThat(result.value().toolName()).isEqualTo("text");
        assertThat(result.value().paramsJson()).isEqualTo("I cannot do that.");
    }
}
