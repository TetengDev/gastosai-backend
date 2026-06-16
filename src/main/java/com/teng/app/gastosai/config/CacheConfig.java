package com.teng.app.gastosai.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caches the deterministic AI insight responses (per user + month) so repeat dashboard loads
 * avoid re-calling the LLM. TTL-bounded and evicted on any expense change (see ExpenseService).
 */
@Configuration
@EnableCaching
public class CacheConfig {

	static final String[] INSIGHT_CACHES = {
			"insightTopCategory", "insightMonthSummary", "insightRecommendations"
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
}
