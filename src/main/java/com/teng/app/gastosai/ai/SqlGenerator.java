package com.teng.app.gastosai.ai;

public interface SqlGenerator {

	String generateSql(String question);

	/**
	 * Classifies a natural-language analytics question into the structured query-intent JSON
	 * (see {@code ai.query.QueryIntent}). Returns {@code null} when the model produces no usable
	 * intent, signalling the caller to fall back to {@link #generateSql(String)}.
	 */
	String classifyQueryIntentJson(String question);

	String generateSummary(String question, String dataJson, String mode);

	String generateInsightSummary(String contextJson, String insightType, String mode);

	ChatToolCall classifyIntent(String message);
}

