package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.config.AiLanguageProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves and validates language codes against the configured allow-list.
 *
 * <p>Two resolution modes, and the difference is the point: {@link #fromCode} is the write path and
 * rejects, {@link #fromCodeOrDefault} is the read path and falls back. A code that was configured
 * when it was stored can be removed later, and a user whose language vanished must still get their
 * insights rather than a 500 on every request.
 */
@Service
public class AiLanguageRegistry {

	private final Map<String, AiLanguage> byCode = new LinkedHashMap<>();

	public AiLanguageRegistry(AiLanguageProperties properties) {
		List<AiLanguageProperties.Entry> configured = properties.getSupported();
		for (int index = 0; index < configured.size(); index++) {
			AiLanguageProperties.Entry entry = configured.get(index);
			if (entry == null) {
				// A sparse index in the configuration — supported[2] set while supported[1] is not —
				// binds as a null element rather than shortening the list.
				throw new IllegalStateException(misconfigured(index) + " is missing.");
			}
			requireConfigured(entry.code(), "code", index);
			requireConfigured(entry.displayName(), "displayName", index);
			String normalized = entry.code().toLowerCase();
			AiLanguage existing = byCode.putIfAbsent(normalized,
					new AiLanguage(entry.code(), entry.displayName()));
			if (existing != null) {
				// A second entry under the same normalized code would otherwise be dropped silently:
				// the picker would be one language short, and the surviving entry would keep the
				// first occurrence's position while showing the last occurrence's display name.
				throw new IllegalStateException(
						"gastos.ai.language.supported has two entries for code '" + normalized
								+ "' ('" + existing.displayName() + "' and '" + entry.displayName()
								+ "') — codes are matched case-insensitively and must be unique.");
			}
		}
		if (!byCode.containsKey(AiLanguage.DEFAULT_CODE)) {
			throw new IllegalStateException(
					"gastos.ai.language.supported must include '" + AiLanguage.DEFAULT_CODE
							+ "' — it is the default and the fallback for every unset value.");
		}
	}

	/**
	 * A missing value used to reach the constructor as a bare {@link NullPointerException}, which
	 * named neither the property nor the entry that was wrong. Startup still fails — it just says
	 * which line of configuration to go and fix.
	 */
	private static void requireConfigured(String value, String field, int index) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					misconfigured(index) + "." + field + " is " + (value == null ? "missing" : "blank")
							+ " — every configured language needs a code and a display name.");
		}
	}

	private static String misconfigured(int index) {
		return "gastos.ai.language.supported[" + index + "]";
	}

	/** In configuration order, which is picker order. */
	public List<AiLanguage> supported() {
		return List.copyOf(byCode.values());
	}

	public AiLanguage defaultLanguage() {
		return byCode.get(AiLanguage.DEFAULT_CODE);
	}

	/** The read path: unset, blank and no-longer-configured all resolve to English. */
	public AiLanguage fromCodeOrDefault(String code) {
		if (code == null || code.isBlank()) {
			return defaultLanguage();
		}
		AiLanguage language = byCode.get(code.strip().toLowerCase());
		return language == null ? defaultLanguage() : language;
	}

	/**
	 * The write path.
	 *
	 * @throws IllegalArgumentException if the code is not configured; the message names the accepted
	 *         values so the boundary can turn it into a 400 without restating them
	 */
	public AiLanguage fromCode(String code) {
		String normalized = code == null ? "" : code.strip().toLowerCase();
		AiLanguage language = byCode.get(normalized);
		if (language == null) {
			throw new IllegalArgumentException(
					"Unsupported language: " + code + ". Accepted values: " + acceptedCodes());
		}
		return language;
	}

	/** {@code "en, fil, ceb, …"} — for error messages and API documentation. */
	public String acceptedCodes() {
		return byCode.values().stream().map(AiLanguage::code).collect(Collectors.joining(", "));
	}
}
