package com.teng.app.gastosai.ai;

/**
 * A language the AI writes user-facing prose in.
 *
 * <p>Instances come from {@link AiLanguageRegistry}, which builds them from configuration. Nothing
 * else should construct one: the registry is what guarantees a code was on the allow-list, and the
 * value reaches a prompt. The single exception is {@link #DEFAULT}, which is English and therefore
 * safe by construction — it exists for the callers that never resolve a user setting at all.
 *
 * @param code        BCP-47 code
 * @param displayName the language's name in its own language
 */
public record AiLanguage(String code, String displayName) {

	/** The code used when a user has not chosen. */
	public static final String DEFAULT_CODE = "en";

	/**
	 * English, for callers with no user setting to resolve — they have no code to look up, so
	 * reaching for the registry would only be ceremony around a constant.
	 */
	public static final AiLanguage DEFAULT = new AiLanguage(DEFAULT_CODE, "English");

	/**
	 * The instruction appended to every prompt that produces user-facing prose. It states the
	 * language outright and protects the parts of the output that are not prose — the peso amounts
	 * and, for the recommendations prompt, the JSON array shape.
	 */
	public String promptInstruction() {
		return "\nWrite all user-facing prose in " + displayName
				+ ", whatever language the input is written in. Keep ₱ amounts, numbers and any"
				+ " required JSON structure exactly as specified above.";
	}
}
