package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiLanguage;
import com.teng.app.gastosai.ai.AiLanguageSettingsService;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.LlmUsage;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiInsightService;
import com.teng.app.gastosai.service.ExpenseService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class AiInsightCacheTest extends PostgresBackedTest {

	@Autowired
	AiInsightService aiInsightService;

	@Autowired
	ExpenseService expenseService;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AiLanguageSettingsService aiLanguageSettingsService;

	@Autowired
	CacheManager cacheManager;

	@MockitoBean
	SqlGenerator sqlGenerator;

	User user;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		user = userRepository.save(User.builder()
				.name("Cache User").email("cache@test.com")
				.password(passwordEncoder.encode("password")).build());
		when(sqlGenerator.generateInsightSummary(any(), eq("month-summary"), eq("plain"), any()))
				.thenReturn(LlmResult.of("A quiet month.", LlmUsage.absent()));
	}

	@Test
	void monthSummary_isCachedThenEvictedOnExpenseChange() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		aiInsightService.getMonthSummary(user, "2026-06");
		// Second call served from cache — generator invoked once.
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		// An expense change evicts the insight caches.
		expenseService.create(new ExpenseRequest(
				new BigDecimal("100.00"), "Food", LocalDateTime.now(), "Lunch", null, null, null, null), user);

		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());
	}

	@Test
	void switchingInsightLanguage_regeneratesRatherThanServingTheOldLanguage() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(AiLanguage.EN));

		user.setInsightLanguage("fil");
		user = userRepository.save(user);

		// Same user, same month — but the language is part of the key, so this is a miss.
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(AiLanguage.FIL));
	}

	@Test
	void unchosenLanguage_alwaysAsksForEnglish() throws Exception {
		when(sqlGenerator.generateInsightSummary(any(), eq("recommendations"), eq("plain"), any()))
				.thenReturn(LlmResult.of("[\"Trim Food spending.\"]", LlmUsage.absent()));

		aiInsightService.getRecommendations(user, "2026-07");
		verify(sqlGenerator, times(1))
				.generateInsightSummary(any(), eq("recommendations"), eq("plain"), eq(AiLanguage.EN));
	}

	/**
	 * The insight caches are shared across tenants and {@code PUT /user/ai-settings} carries no
	 * rate limit, so a language change must not be a lever one user can pull to force every other
	 * tenant's next insight back through the paid LLM path.
	 */
	@Test
	void oneUsersLanguageSwitch_leavesEveryOtherUsersCacheWarm() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		User other = userRepository.save(User.builder()
				.name("Other User").email("cache-other@test.com")
				.password(passwordEncoder.encode("password")).build());
		aiInsightService.getMonthSummary(other, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		aiLanguageSettingsService.update(other.getEmail(), new AiSettingsRequest(null, null, "fil", null));

		// The switcher's own entry is gone; the bystander's is untouched.
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		assertThat(cachedKeysOf("insightMonthSummary"))
				.noneMatch(key -> key.startsWith(other.getId() + "-"))
				.anyMatch(key -> key.startsWith(user.getId() + "-"));
	}

	@Test
	void languageSwitch_leavesTheNonProseCacheAlone() throws Exception {
		aiInsightService.getTopCategory(user, "2026-06");
		assertThat(cachedKeysOf("insightTopCategory")).anyMatch(key -> key.startsWith(user.getId() + "-"));

		aiLanguageSettingsService.update(user.getEmail(), new AiSettingsRequest(null, null, "fil", null));

		// getTopCategory returns a category name and two numbers — no language can stale it.
		assertThat(cachedKeysOf("insightTopCategory")).anyMatch(key -> key.startsWith(user.getId() + "-"));
	}

	private List<String> cachedKeysOf(String cacheName) {
		Object nativeCache = cacheManager.getCache(cacheName).getNativeCache();
		return ((com.github.benmanes.caffeine.cache.Cache<?, ?>) nativeCache).asMap().keySet().stream()
				.map(String::valueOf)
				.toList();
	}
}
