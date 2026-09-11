package com.teng.app.gastosai.ai;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The language the AI writes user-facing prose in.
 *
 * <p>The set is a closed allow-list on purpose: the value reaches a prompt, so accepting an
 * arbitrary string would make the setting a prompt-injection surface. Only the constants below
 * ever reach {@link #promptInstruction()}; user input is matched against {@link #fromCode} and
 * rejected otherwise.
 *
 * <p>{@link #DEFAULT} is what a user who has chosen nothing gets. Leaving it to the model — which
 * is what a bare "for Filipino users" cue does — makes the language vary per call.
 */
public enum AiLanguage {

	EN("en", "English"),
	FIL("fil", "Filipino (Tagalog)");

	/** Used when the user has not chosen a language. */
	public static final AiLanguage DEFAULT = EN;

	private final String code;
	private final String displayName;

	AiLanguage(String code, String displayName) {
		this.code = code;
		this.displayName = displayName;
	}

	/** The BCP-47 code stored on the user row and published in the contract. */
	public String code() {
		return code;
	}

	/**
	 * The instruction appended to every prompt that produces user-facing prose. It states the
	 * language outright and protects the parts of the output that are not prose — the peso
	 * amounts and, for the recommendations prompt, the JSON array shape.
	 */
	public String promptInstruction() {
		return "\nWrite all user-facing prose in " + displayName
				+ ", whatever language the input is written in. Keep ₱ amounts, numbers and any"
				+ " required JSON structure exactly as specified above.";
	}

	/** Resolves a stored or submitted code, falling back to {@link #DEFAULT} when unset. */
	public static AiLanguage fromCodeOrDefault(String code) {
		return (code == null || code.isBlank()) ? DEFAULT : fromCode(code);
	}

	/**
	 * @throws IllegalArgumentException if the code is not on the allow-list; the message names the
	 *         accepted values so the boundary can turn it into a 400 without restating them
	 */
	public static AiLanguage fromCode(String code) {
		String normalized = code == null ? "" : code.strip().toLowerCase();
		return Arrays.stream(values())
				.filter(language -> language.code.equals(normalized))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"Unsupported language: " + code + ". Accepted values: " + acceptedCodes()));
	}

	/** {@code "en, fil"} — the accepted values, for error messages and API documentation. */
	public static String acceptedCodes() {
		return Arrays.stream(values()).map(AiLanguage::code).collect(Collectors.joining(", "));
	}
}
