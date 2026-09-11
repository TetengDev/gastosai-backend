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
		for (AiLanguageProperties.Entry entry : properties.getSupported()) {
			byCode.put(entry.code().toLowerCase(), new AiLanguage(entry.code(), entry.displayName()));
		}
		if (!byCode.containsKey(AiLanguage.DEFAULT_CODE)) {
			throw new IllegalStateException(
					"gastos.ai.language.supported must include '" + AiLanguage.DEFAULT_CODE
							+ "' — it is the default and the fallback for every unset value.");
		}
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
