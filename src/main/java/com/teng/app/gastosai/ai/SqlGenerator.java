package com.teng.app.gastosai.ai;

public interface SqlGenerator {

    LlmResult<String> generateSql(String question);

    LlmResult<String> classifyQueryIntentJson(String question);

    LlmResult<String> generateSummary(String question, String dataJson, String mode);

    LlmResult<String> generateInsightSummary(String contextJson, String insightType, String mode);

    LlmResult<ChatToolCall> classifyIntent(String message);
}
