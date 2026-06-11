package com.teng.app.gastosai.ai;

public interface SqlGenerator {

	String generateSql(String question);

	String generateSummary(String question, String dataJson, String mode);

	String generateInsightSummary(String contextJson, String insightType, String mode);
}

