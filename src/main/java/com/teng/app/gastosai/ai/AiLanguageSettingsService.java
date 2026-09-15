package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.config.CacheConfig.InsightCacheEvictor;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.AiSettingsResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.UserAiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * The language half of {@code /user/ai-settings}: validates the submitted codes against
 * {@link AiLanguageRegistry}, persists them, and keeps the cached insights honest when the insight
 * language changes. API-key handling stays in {@link UserAiSettingsService}; this type wraps it so
 * the controller still talks to one service.
 *
 * <p>It lives beside {@link AiLanguage} because the allow-list, the prompt wording and the cache
 * key are one concern: the language is the only user-supplied string that reaches a prompt.
 */
@Service
@RequiredArgsConstructor
public class AiLanguageSettingsService {

	private final UserAiSettingsService keySettings;
	private final UserRepository userRepository;
	private final InsightCacheEvictor insightCaches;
	private final AiLanguageRegistry languages;

	@Transactional(readOnly = true)
	public AiSettingsResponse get(String email) {
		return withLanguages(keySettings.get(email), findUser(email));
	}

	/**
	 * A null language leaves the current choice alone; a blank one clears it, returning the user to
	 * the {@link AiLanguage#DEFAULT_CODE} the API reports as null. Anything else must be configured.
	 */
	@Transactional
	public AiSettingsResponse update(String email, AiSettingsRequest request) {
		String insightLanguage = validate(request.insightLanguage(), "insightLanguage");
		String chatLanguage = validate(request.chatLanguage(), "chatLanguage");

		AiSettingsResponse keys = keySettings.update(email, request);
		User user = findUser(email);
		String previousInsightLanguage = user.getInsightLanguage();
		if (request.insightLanguage() != null) {
			user.setInsightLanguage(insightLanguage);
		}
		if (request.chatLanguage() != null) {
			user.setChatLanguage(chatLanguage);
		}
		User saved = userRepository.save(user);

		// The cache key carries the language, so a switch already misses rather than serving the old
		// language back. Evicting as well keeps a switch-and-switch-back from replaying stale text.
		if (!Objects.equals(previousInsightLanguage, saved.getInsightLanguage())) {
			insightCaches.evictLanguageKeyedAfterCommit(saved.getId());
		}
		return withLanguages(keys, saved);
	}

	@Transactional
	public AiSettingsResponse clear(String email, String provider) {
		return withLanguages(keySettings.clear(email, provider), findUser(email));
	}

	private String validate(String code, String field) {
		if (code == null || code.isBlank()) {
			return null;
		}
		try {
			return languages.fromCode(code).code();
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					field + " must be one of: " + languages.acceptedCodes());
		}
	}

	private AiSettingsResponse withLanguages(AiSettingsResponse keys, User user) {
		return keys.withLanguages(user.getInsightLanguage(), user.getChatLanguage());
	}

	private User findUser(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}
}
