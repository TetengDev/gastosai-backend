package com.teng.app.gastosai.ai;

public interface SqlGenerator {

    LlmResult<String> generateSql(String question);

    LlmResult<String> classifyQueryIntentJson(String question);

    /**
     * Summarizes query results in the given persona and language.
     *
     * <p>The language is explicit rather than left to the model: a prompt that names only the
     * audience picks a language per call.
     */
    LlmResult<String> generateSummary(String question, String dataJson, String mode, AiLanguage language);

    /** Summarizes in {@link AiLanguage#DEFAULT} — for callers that do not resolve a user setting. */
    default LlmResult<String> generateSummary(String question, String dataJson, String mode) {
        return generateSummary(question, dataJson, mode, AiLanguage.DEFAULT);
    }

    LlmResult<String> generateInsightSummary(String contextJson, String insightType, String mode, AiLanguage language);

    /** Generates an insight in {@link AiLanguage#DEFAULT}. */
    default LlmResult<String> generateInsightSummary(String contextJson, String insightType, String mode) {
        return generateInsightSummary(contextJson, insightType, mode, AiLanguage.DEFAULT);
    }

    LlmResult<ChatToolCall> classifyIntent(String message);
}
