package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ClaudeExpenseParser;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeExpenseParserTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient claudeRestClient;

    @Mock
    ClaudeProperties claudeProperties;

    @InjectMocks
    ClaudeExpenseParser parser;

    private String claudeTextResponse(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private String expenseJson(String amount, String category, String confidence) {
        return "{\\\\\"amount\\\\\":\\\\\"" + amount + "\\\\\","
                + "\\\\\"category\\\\\":\\\\\"" + category + "\\\\\","
                + "\\\\\"date\\\\\":\\\\\"2026-06-14T12:00:00\\\\\","
                + "\\\\\"description\\\\\":\\\\\"Test expense\\\\\","
                + "\\\\\"confidence\\\\\":\\\\\"" + confidence + "\\\\\"}";
    }

    // ── API key guard ─────────────────────────────────────────────────────────

    @Test
    void parse_nullApiKey_throws() {
        when(claudeProperties.getApiKey()).thenReturn(null);

        assertThatThrownBy(() -> parser.parse("spent 500 on food"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAUDE_API_KEY");
    }

    @Test
    void parse_blankApiKey_throws() {
        when(claudeProperties.getApiKey()).thenReturn("");

        assertThatThrownBy(() -> parser.parse("spent 500 on food"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAUDE_API_KEY");
    }

    // ── happy path — bare JSON in response text ───────────────────────────────

    @Test
    void parse_bareJsonResponse_parsedCorrectly() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        String expenseObj = "{\\\"amount\\\":\\\"500.00\\\",\\\"category\\\":\\\"Food\\\","
                + "\\\"date\\\":\\\"2026-06-14T12:00:00\\\",\\\"description\\\":\\\"Lunch\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\"}";
        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeTextResponse(expenseObj));

        ParsedExpenseResult result = parser.parse("spent 500 on lunch");

        assertThat(result.amount()).isEqualByComparingTo("500.00");
        assertThat(result.category()).isEqualTo("Food");
        assertThat(result.confidence()).isEqualTo("HIGH");
        assertThat(result.saveable()).isTrue();
    }

    // ── happy path — fenced JSON response ─────────────────────────────────────

    @Test
    void parse_fencedJsonResponse_extractsAndParses() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        String fencedObj = "```json\\n{\\\"amount\\\":\\\"300.00\\\",\\\"category\\\":\\\"Transport\\\","
                + "\\\"date\\\":\\\"2026-06-14T08:00:00\\\",\\\"description\\\":\\\"Grab\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\"}\\n```";
        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeTextResponse(fencedObj));

        ParsedExpenseResult result = parser.parse("300 grab ride");

        assertThat(result.amount()).isEqualByComparingTo("300.00");
        assertThat(result.category()).isEqualTo("Transport");
        assertThat(result.saveable()).isTrue();
    }

    // ── low confidence → not saveable ────────────────────────────────────────

    @Test
    void parse_lowConfidence_notSaveable() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        String expenseObj = "{\\\"amount\\\":\\\"100.00\\\",\\\"category\\\":\\\"Food\\\","
                + "\\\"date\\\":\\\"2026-06-14T12:00:00\\\",\\\"description\\\":\\\"Something\\\","
                + "\\\"confidence\\\":\\\"LOW\\\"}";
        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeTextResponse(expenseObj));

        ParsedExpenseResult result = parser.parse("something");

        assertThat(result.saveable()).isFalse();
        assertThat(result.hint()).isNotNull();
    }

    // ── description field populated ───────────────────────────────────────────

    @Test
    void parse_descriptionPopulated() {
        when(claudeProperties.getApiKey()).thenReturn("sk-ant-test");
        when(claudeProperties.getModel()).thenReturn("claude-3-5-sonnet-20241022");

        String expenseObj = "{\\\"amount\\\":\\\"200.00\\\",\\\"category\\\":\\\"Utilities\\\","
                + "\\\"date\\\":\\\"2026-06-14T09:00:00\\\",\\\"description\\\":\\\"Electric bill\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\"}";
        when(claudeRestClient.post()
                .uri(anyString())
                .contentType(any())
                .body(anyString())
                .retrieve()
                .body(String.class))
                .thenReturn(claudeTextResponse(expenseObj));

        ParsedExpenseResult result = parser.parse("200 electric bill");

        assertThat(result.description()).isEqualTo("Electric bill");
    }
}
