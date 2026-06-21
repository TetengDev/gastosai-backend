package com.teng.app.gastosai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cross-instance fixed-window store backed by Redis (atomic INCR + TTL). Active only when
 * {@code gastos.ratelimit.redis.enabled=true}; otherwise {@link InMemoryRateLimiterStore} is used and
 * no Redis connection is required. The window key embeds the epoch-minute so it self-expires.
 */
@Component
@ConditionalOnProperty(name = "gastos.ratelimit.redis.enabled", havingValue = "true")
public class RedisRateLimiterStore implements RateLimiterStore {

	private final StringRedisTemplate redis;

	public RedisRateLimiterStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean tryAcquire(String key, int limitPerMinute) {
		long windowMinute = System.currentTimeMillis() / 60_000L;
		String redisKey = "rl:" + key + ":" + windowMinute;
		Long count = redis.opsForValue().increment(redisKey);
		if (count != null && count == 1L) {
			// Slightly longer than the window so the counter survives clock skew, then auto-expires.
			redis.expire(redisKey, Duration.ofSeconds(70));
		}
		return count != null && count <= limitPerMinute;
	}
}
