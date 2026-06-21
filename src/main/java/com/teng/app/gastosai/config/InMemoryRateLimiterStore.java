package com.teng.app.gastosai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default per-JVM fixed-window store. Correct for a single instance; on a multi-instance deployment
 * the effective limit is N × the configured rate, so set {@code gastos.ratelimit.redis.enabled=true}
 * to use {@link RedisRateLimiterStore} before scaling out.
 */
@Component
@ConditionalOnProperty(name = "gastos.ratelimit.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiterStore implements RateLimiterStore {

	private static final long WINDOW_MILLIS = 60_000L;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	@Override
	public boolean tryAcquire(String key, int limitPerMinute) {
		long now = System.currentTimeMillis();
		Window window = windows.computeIfAbsent(key, k -> new Window(now));
		synchronized (window) {
			if (now - window.start >= WINDOW_MILLIS) {
				window.start = now;
				window.count = 0;
			}
			if (window.count >= limitPerMinute) {
				return false;
			}
			window.count++;
			return true;
		}
	}

	private static final class Window {
		private long start;
		private int count;

		private Window(long start) {
			this.start = start;
		}
	}
}
