package com.teng.app.gastosai;

import com.teng.app.gastosai.config.InMemoryRateLimiterStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterStoreTest {

	@Test
	void allowsUpToLimit_thenDenies() {
		InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
		assertThat(store.tryAcquire("k", 3)).isTrue();
		assertThat(store.tryAcquire("k", 3)).isTrue();
		assertThat(store.tryAcquire("k", 3)).isTrue();
		assertThat(store.tryAcquire("k", 3)).isFalse();
	}

	@Test
	void keysAreIndependent() {
		InMemoryRateLimiterStore store = new InMemoryRateLimiterStore();
		assertThat(store.tryAcquire("a", 1)).isTrue();
		assertThat(store.tryAcquire("a", 1)).isFalse();
		assertThat(store.tryAcquire("b", 1)).isTrue();
	}
}
