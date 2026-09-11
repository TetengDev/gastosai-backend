package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.config.CacheConfig;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.AiSettingsResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.UserAiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * The language half of {@code /user/ai-settings}: validates the submitted codes against
 * {@link AiLanguage}, persists them, and keeps the cached insights honest when the insight
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
	private final CacheManager cacheManager;

	@Transactional(readOnly = true)
	public AiSettingsResponse get(String email) {
		return withLanguages(keySettings.get(email), findUser(email));
	}

	/**
	 * A null language leaves the current choice alone; a blank one clears it, returning the user to
	 * the {@link AiLanguage#DEFAULT} the API reports as null. Anything else must be on the
	 * allow-list.
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
			evictAfterCommit(saved.getId());
		}
		return withLanguages(keys, saved);
	}

	@Transactional
	public AiSettingsResponse clear(String email, String provider) {
		return withLanguages(keySettings.clear(email, provider), findUser(email));
	}

	private static String validate(String code, String field) {
		if (code == null || code.isBlank()) {
			return null;
		}
		try {
			return AiLanguage.fromCode(code).code();
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					field + " must be one of: " + AiLanguage.acceptedCodes());
		}
	}

	/**
	 * Evicts once the language change has actually committed. Clearing inside the transaction
	 * would throw away warm entries for a write that then rolled back.
	 */
	private void evictAfterCommit(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			evictInsightsOf(userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				evictInsightsOf(userId);
			}
		});
	}

	/**
	 * Drops only this user's prose insights. {@code cache.clear()} would be far simpler, but the
	 * insight caches are shared across every tenant and this endpoint carries no rate limit — one
	 * user toggling their language would force every other tenant's next insight back through the
	 * paid LLM path and onto their own {@code ai_usage} meter.
	 */
	private void evictInsightsOf(Long userId) {
		String prefix = userId + "-";
		for (String cacheName : CacheConfig.LANGUAGE_KEYED_INSIGHT_CACHES) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache == null) {
				continue;
			}
			// Caffeine when caching is enabled; a NoOpCache has nothing to walk.
			if (cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
				caffeine.asMap().keySet()
						.removeIf(key -> key instanceof String entry && entry.startsWith(prefix));
			}
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
