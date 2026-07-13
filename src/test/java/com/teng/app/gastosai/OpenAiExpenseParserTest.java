package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.OpenAiExpenseParser;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
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
class OpenAiExpenseParserTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient openAiRestClient;

    @Mock
    OpenAiProperties openAiProperties;

    @InjectMocks
    OpenAiExpenseParser parser;

    private String openAiResponse(String innerJson) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + innerJson + "\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50}}";
    }

    private String openAiResponseNoUsage(String innerJson) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + innerJson + "\"}}]}";
    }

    private String expenseJson(String amount, String category, String confidence) {
        return "{\\\"amount\\\":\\\"" + amount + "\\\","
                + "\\\"category\\\":\\\"" + category + "\\\","
                + "\\\"date\\\":\\\"2026-06-14T12:00:00\\\","
                + "\\\"description\\\":\\\"Test\\\","
                + "\\\"confidence\\\":\\\"" + confidence + "\\\"}";
    }

    @Test
    void parse_nullApiKey_throws() {
        when(openAiProperties.getApiKey()).thenReturn(null);

        assertThatThrownBy(() -> parser.parse("spent 500 on food"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void parse_blankApiKey_throws() {
        when(openAiProperties.getApiKey()).thenReturn("");

        assertThatThrownBy(() -> parser.parse("spent 500 on food"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void parse_highConfidence_saveableTrue() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(expenseJson("500.00", "Food", "HIGH")));

        LlmResult<ParsedExpenseResult> result = parser.parse("500 lunch food");

        assertThat(result.value().amount()).isEqualByComparingTo("500.00");
        assertThat(result.value().category()).isEqualTo("Food");
        assertThat(result.value().confidence()).isEqualTo("HIGH");
        assertThat(result.value().saveable()).isTrue();
        assertThat(result.value().hint()).isNull();
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
    }

    @Test
    void parse_usageAbsentWhenMissingFromResponse() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponseNoUsage(expenseJson("200.00", "Food", "HIGH")));

        LlmResult<ParsedExpenseResult> result = parser.parse("200 food");

        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().outputTokens()).isNull();
    }

    @Test
    void parse_lowConfidence_notSaveable() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(expenseJson("100.00", "Food", "LOW")));

        LlmResult<ParsedExpenseResult> result = parser.parse("something unclear");

        assertThat(result.value().saveable()).isFalse();
        assertThat(result.value().hint()).isNotNull();
    }

    @Test
    void parse_zeroAmount_notSaveable() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(expenseJson("0", "Food", "HIGH")));

        LlmResult<ParsedExpenseResult> result = parser.parse("free food");

        assertThat(result.value().saveable()).isFalse();
    }

    @Test
    void parse_descriptionAndCategoryPopulated() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String innerJson = "{\\\"amount\\\":\\\"150.00\\\",\\\"category\\\":\\\"Transport\\\","
                + "\\\"date\\\":\\\"2026-06-14T07:30:00\\\","
                + "\\\"description\\\":\\\"Morning jeep ride\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\"}";

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(innerJson));

        LlmResult<ParsedExpenseResult> result = parser.parse("150 jeep");

        assertThat(result.value().category()).isEqualTo("Transport");
        assertThat(result.value().description()).isEqualTo("Morning jeep ride");
    }

    @Test
    void parse_missingCategory_defaultsToUncategorized() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        String innerJson = "{\\\"amount\\\":\\\"200.00\\\","
                + "\\\"date\\\":\\\"2026-06-14T12:00:00\\\","
                + "\\\"description\\\":\\\"Random\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\"}";

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(innerJson));

        LlmResult<ParsedExpenseResult> result = parser.parse("200 something");

        assertThat(result.value().category()).isEqualTo("Uncategorized");
    }

    @Test
    void parse_validDate_populatedInResult() {
        when(openAiProperties.getApiKey()).thenReturn("sk-test");
        when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");

        when(openAiRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(openAiResponse(expenseJson("250.00", "Groceries", "HIGH")));

        LlmResult<ParsedExpenseResult> result = parser.parse("250 groceries");

        assertThat(result.value().date()).isNotNull();
        assertThat(result.value().date().getYear()).isEqualTo(2026);
    }
}
