package com.teng.app.gastosai.dto;

/**
 * @param insightLanguage BCP-47 code for AI insights; null means the user has not chosen one
 * @param chatLanguage    BCP-47 code for the assistant; null means the user has not chosen one
 */
public record AiSettingsResponse(boolean openaiKeySet, boolean claudeKeySet, boolean aiAvailable,
		String insightLanguage, String chatLanguage) {

	/**
	 * The key-only view, with both languages absent. Callers that handle API keys build this and
	 * the language layer fills the rest in via {@link #withLanguages}.
	 */
	public AiSettingsResponse(boolean openaiKeySet, boolean claudeKeySet, boolean aiAvailable) {
		this(openaiKeySet, claudeKeySet, aiAvailable, null, null);
	}

	public AiSettingsResponse withLanguages(String insightLanguage, String chatLanguage) {
		return new AiSettingsResponse(openaiKeySet, claudeKeySet, aiAvailable, insightLanguage, chatLanguage);
	}
}
