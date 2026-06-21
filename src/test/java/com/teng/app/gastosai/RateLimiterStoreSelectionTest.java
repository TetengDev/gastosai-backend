package com.teng.app.gastosai;

import com.teng.app.gastosai.config.InMemoryRateLimiterStore;
import com.teng.app.gastosai.config.RateLimiterStore;
import com.teng.app.gastosai.config.RedisRateLimiterStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** By default (Redis disabled) the in-memory store is wired and the Redis store is absent. */
@SpringBootTest
class RateLimiterStoreSelectionTest {

	@Autowired RateLimiterStore store;
	@Autowired DefaultListableBeanFactory beanFactory;

	@Test
	void defaultStoreIsInMemory() {
		assertThat(store).isInstanceOf(InMemoryRateLimiterStore.class);
	}

	@Test
	void redisStoreNotLoadedByDefault() {
		assertThat(beanFactory.getBeanNamesForType(RedisRateLimiterStore.class)).isEmpty();
	}
}
