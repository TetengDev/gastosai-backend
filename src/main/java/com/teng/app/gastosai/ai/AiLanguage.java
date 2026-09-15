package com.teng.app.gastosai.ai;

import java.util.Objects;

/**
 * A language the AI writes user-facing prose in.
 *
 * <p>Instances come from {@link AiLanguageRegistry}, which builds them from configuration. Nothing
 * else can construct one: the constructor is package-private, so the registry is what guarantees a
 * code was on the allow-list before the value reaches a prompt. The single exception is
 * {@link #DEFAULT}, which is English and therefore safe by construction — it exists for the callers
 * that never resolve a user setting at all.
 *
 * <p>This is a class rather than a record because a record's canonical constructor may not be less
 * accessible than the record itself (JLS 8.10.4.1), and this type has to stay public: it appears in
 * the signatures the service layer calls. The accessors keep record naming so callers read the
 * same.
 */
public final class AiLanguage {

	/** The code used when a user has not chosen. */
	public static final String DEFAULT_CODE = "en";

	/**
	 * English, for callers with no user setting to resolve — they have no code to look up, so
	 * reaching for the registry would only be ceremony around a constant.
	 */
	public static final AiLanguage DEFAULT = new AiLanguage(DEFAULT_CODE, "English");

	private final String code;

	private final String displayName;

	/**
	 * Package-private on purpose: see the class comment. Widening this reopens the hole the
	 * allow-list exists to close.
	 *
	 * @param code        BCP-47 code
	 * @param displayName the language's name in its own language
	 */
	AiLanguage(String code, String displayName) {
		this.code = code;
		this.displayName = displayName;
	}

	/** BCP-47 code. */
	public String code() {
		return code;
	}

	/** The language's name in its own language. */
	public String displayName() {
		return displayName;
	}

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

	/** Value equality, as the record gave: the language is part of an insight cache key. */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof AiLanguage that)) {
			return false;
		}
		return Objects.equals(code, that.code) && Objects.equals(displayName, that.displayName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(code, displayName);
	}

	@Override
	public String toString() {
		return "AiLanguage[code=" + code + ", displayName=" + displayName + "]";
	}
}
