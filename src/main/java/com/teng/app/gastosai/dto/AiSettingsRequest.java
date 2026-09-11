package com.teng.app.gastosai.dto;

/**
 * @param insightLanguage BCP-47 code for AI insights, or null to leave the current choice alone
 * @param chatLanguage    BCP-47 code for the assistant, or null to leave the current choice alone
 */
public record AiSettingsRequest(String openaiApiKey, String claudeApiKey,
		String insightLanguage, String chatLanguage) {
}
