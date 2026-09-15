package com.teng.app.gastosai.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * Caches the deterministic AI insight responses (per user + month) so repeat dashboard loads
 * avoid re-calling the LLM. TTL-bounded and evicted on any expense change or insight-language
 * change, both through {@link InsightCacheEvictor}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

	static final String[] INSIGHT_CACHES = {
			"insightTopCategory", "insightMonthSummary", "insightRecommendations"
	};

	/**
	 * The insight caches holding AI-written prose, whose key carries the language. A language
	 * change invalidates the caller's entries here and nowhere else — {@code insightTopCategory}
	 * is a category name and two numbers, so no language change can stale it.
	 */
	static final String[] LANGUAGE_KEYED_INSIGHT_CACHES = {
			"insightMonthSummary", "insightRecommendations"
	};

	@Bean
	public CacheManager cacheManager(CacheProperties properties) {
		if (!properties.isEnabled()) {
			return new NoOpCacheManager();
		}
		CaffeineCacheManager manager = new CaffeineCacheManager(INSIGHT_CACHES);
		manager.setCaffeine(Caffeine.newBuilder()
				.expireAfterWrite(properties.getTtlMinutes(), TimeUnit.MINUTES)
				.maximumSize(properties.getMaxSize()));
		return manager;
	}

	@Bean
	public InsightCacheEvictor insightCacheEvictor(CacheManager cacheManager) {
		return new InsightCacheEvictor(cacheManager);
	}

	/**
	 * The one owner of per-user insight eviction. It lives here because the cache names are declared
	 * here: a fourth insight cache is then added in one place, and every writer that can stale an
	 * insight picks it up. Two services carried a byte-identical copy of this walk before (TEN-393),
	 * and one of them hand-copied the cache-name list because it could not see this one.
	 *
	 * <p>Drops one user's entries and nobody else's. {@code cache.clear()} would be far simpler, but
	 * the insight caches are shared across every tenant: one user adding a lunch — or toggling their
	 * language on an endpoint with no rate limit — would force every other tenant's next insight back
	 * through the paid LLM path and onto their own {@code ai_usage} meter. The keys are
	 * {@code userId + "-" + month} and {@code userId + "-" + month + "-" + languageCode}, so the
	 * prefix match cannot cross tenants.
	 *
	 * <p>Every month of the user's is dropped, not just the month written to: an insight for month
	 * M carries the previous month's total (see {@code AiInsightService.buildContext}), so a write
	 * to M stales M and M+1, and an edit that moves a date stales the months on both sides of the
	 * move. Enumerating those is more ways to be wrong than a user-wide prefix sweep is worth.
	 *
	 * <p>The sweep is a scan of each cache rather than a keyed removal, so its cost follows every
	 * tenant's entries. That is bounded: {@code CacheProperties.maxSize} caps each cache at 10,000
	 * entries, so a full write path walks at most 30,000 keys — tens of microseconds, against a
	 * transaction that has already committed.
	 */
	public static class InsightCacheEvictor {

		private final CacheManager cacheManager;

		InsightCacheEvictor(CacheManager cacheManager) {
			this.cacheManager = cacheManager;
		}

		/**
		 * Every insight of the user's, once the write commits — the expense path, where any figure
		 * an insight is built from may have moved.
		 */
		public void evictAllAfterCommit(Long userId) {
			afterCommit(userId, INSIGHT_CACHES);
		}

		/**
		 * Only the prose insights, once the write commits — the insight-language path. A language
		 * switch cannot stale {@code insightTopCategory}, which is a category name and two numbers.
		 */
		public void evictLanguageKeyedAfterCommit(Long userId) {
			afterCommit(userId, LANGUAGE_KEYED_INSIGHT_CACHES);
		}

		/**
		 * Evicting inside the transaction would throw warm entries away for a write that then rolled
		 * back, and — worse on the expense path — would reopen the window where a concurrent insight
		 * read repopulates the cache from pre-commit data and leaves it stale until the TTL.
		 */
		private void afterCommit(Long userId, String[] cacheNames) {
			if (userId == null) {
				return;
			}
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				evict(userId, cacheNames);
				return;
			}
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					evict(userId, cacheNames);
				}
			});
		}

		private void evict(Long userId, String[] cacheNames) {
			String prefix = userId + "-";
			for (String cacheName : cacheNames) {
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
	}
}
