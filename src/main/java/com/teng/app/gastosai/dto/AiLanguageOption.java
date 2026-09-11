package com.teng.app.gastosai.dto;

/**
 * A language a client may offer for AI prose.
 *
 * @param code        BCP-47 code to store in the AI settings
 * @param displayName the language's name in its own language, for the picker
 */
public record AiLanguageOption(String code, String displayName) {
}
