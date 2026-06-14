package com.teng.app.gastosai;

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

    // OpenAI response wraps content in choices[0].message.content as a JSON string
    private String openAiResponse(String innerJson) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + innerJson + "\"}}]}";
    }

    private String expenseJson(String amount, String category, String confidence) {
        return "{\\\"amount\\\":\\\"" + amount + "\\\","
                + "\\\"category\\\":\\\"" + category + "\\\","
                + "\\\"date\\\":\\\"2026-06-14T12:00:00\\\","
                + "\\\"description\\\":\\\"Test\\\","
                + "\\\"confidence\\\":\\\"" + confidence + "\\\"}";
    }

    // ── API key guard ─────────────────────────────────────────────────────────

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

    // ── happy path — high confidence ─────────────────────────────────────────

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

        ParsedExpenseResult result = parser.parse("500 lunch food");

        assertThat(result.amount()).isEqualByComparingTo("500.00");
        assertThat(result.category()).isEqualTo("Food");
        assertThat(result.confidence()).isEqualTo("HIGH");
        assertThat(result.saveable()).isTrue();
        assertThat(result.hint()).isNull();
    }

    // ── low confidence → not saveable ────────────────────────────────────────

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

        ParsedExpenseResult result = parser.parse("something unclear");

        assertThat(result.saveable()).isFalse();
        assertThat(result.hint()).isNotNull();
    }

    // ── zero amount → not saveable ────────────────────────────────────────────

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

        ParsedExpenseResult result = parser.parse("free food");

        assertThat(result.saveable()).isFalse();
    }

    // ── description and category fields ──────────────────────────────────────

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

        ParsedExpenseResult result = parser.parse("150 jeep");

        assertThat(result.category()).isEqualTo("Transport");
        assertThat(result.description()).isEqualTo("Morning jeep ride");
    }

    // ── missing category defaults to Uncategorized ────────────────────────────

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

        ParsedExpenseResult result = parser.parse("200 something");

        assertThat(result.category()).isEqualTo("Uncategorized");
    }

    // ── date field populated ──────────────────────────────────────────────────

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

        ParsedExpenseResult result = parser.parse("250 groceries");

        assertThat(result.date()).isNotNull();
        assertThat(result.date().getYear()).isEqualTo(2026);
    }
}
