package com.teng.app.gastosai.config;

/**
 * Fixed-window rate-limit counter, abstracted so the interceptors don't care whether the window
 * lives in-process or in a shared store. Two impls: {@link InMemoryRateLimiterStore} (default,
 * single-instance) and {@link RedisRateLimiterStore} (opt-in, cross-instance for horizontal scale).
 */
public interface RateLimiterStore {

	/**
	 * Records one hit for {@code key} in the current one-minute window.
	 *
	 * @return {@code true} if the hit is within {@code limitPerMinute}, {@code false} if the window is full.
	 */
	boolean tryAcquire(String key, int limitPerMinute);
}
