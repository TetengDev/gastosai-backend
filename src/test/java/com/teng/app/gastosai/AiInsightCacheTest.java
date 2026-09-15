package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiLanguage;
import com.teng.app.gastosai.ai.AiLanguageSettingsService;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.LlmUsage;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.entity.Role;
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

	/** Configured in application.properties; built here rather than resolved, to pin the wording. */
	static final AiLanguage FILIPINO = new AiLanguage("fil", "Filipino");
	static final AiLanguage JAPANESE = new AiLanguage("ja", "日本語");

	User user;

	@BeforeEach
	void setUp() {
		// Users are recreated with fresh ids each test, so entries left by the previous one would
		// otherwise linger under ids nothing matches — and a size assertion would count them.
		cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
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
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(AiLanguage.DEFAULT));

		user.setInsightLanguage("fil");
		user = userRepository.save(user);

		// Same user, same month — but the language is part of the key, so this is a miss.
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(FILIPINO));

		// And a third language, which was never an enum constant: the key carries whatever is
		// configured, so no new cache dimension is needed to add one.
		user.setInsightLanguage("ja");
		user = userRepository.save(user);
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(JAPANESE));
	}

	/**
	 * The eviction path, not the key. The key alone makes a switch a miss; eviction is what keeps a
	 * switch-and-switch-back from replaying stale text, and it was got wrong once already.
	 */
	@Test
	void switchingToAThirdLanguageAndBack_doesNotReplayTheFirstLanguagesText() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(AiLanguage.DEFAULT));

		aiLanguageSettingsService.update(user.getEmail(), new AiSettingsRequest(null, null, "ja", null));
		user = userRepository.findById(user.getId()).orElseThrow();
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), any(), any(), eq(JAPANESE));

		aiLanguageSettingsService.update(user.getEmail(), new AiSettingsRequest(null, null, "en", null));
		user = userRepository.findById(user.getId()).orElseThrow();
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), any(), any(), eq(AiLanguage.DEFAULT));
	}

	@Test
	void unchosenLanguage_alwaysAsksForEnglish() throws Exception {
		when(sqlGenerator.generateInsightSummary(any(), eq("recommendations"), eq("plain"), any()))
				.thenReturn(LlmResult.of("[\"Trim Food spending.\"]", LlmUsage.absent()));

		aiInsightService.getRecommendations(user, "2026-07");
		verify(sqlGenerator, times(1))
				.generateInsightSummary(any(), eq("recommendations"), eq("plain"), eq(AiLanguage.DEFAULT));
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

	/**
	 * An expense write is the most common authenticated write there is, and the insight caches are
	 * shared across tenants: one user adding a lunch must not discard every other tenant's cached
	 * insights and push their next insight request onto their own {@code ai_usage} meter (TEN-385).
	 */
	@Test
	void oneUsersExpenseWrite_leavesEveryOtherUsersCacheWarm() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		User other = userRepository.save(User.builder()
				.name("Other User").email("cache-other@test.com")
				.password(passwordEncoder.encode("password")).build());
		aiInsightService.getMonthSummary(other, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		expenseService.create(new ExpenseRequest(
				new BigDecimal("100.00"), "Food", LocalDateTime.now(), "Lunch", null, null, null, null), other);

		// The bystander's entry is still served from cache; the writer's is gone.
		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"), any());

		assertThat(cachedKeysOf("insightMonthSummary"))
				.noneMatch(key -> key.startsWith(other.getId() + "-"))
				.anyMatch(key -> key.startsWith(user.getId() + "-"));
	}

	/**
	 * Every month of the writer's is dropped, not only the month the row sits in: an insight for
	 * one month carries the previous month's total, and an edit can move a date across months.
	 */
	@Test
	void anExpenseWrite_invalidatesEveryMonthOfTheWritingUser() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-05");
		aiInsightService.getMonthSummary(user, "2026-07");
		assertThat(cachedKeysOf("insightMonthSummary")).hasSize(2);

		expenseService.create(new ExpenseRequest(
				new BigDecimal("100.00"), "Food", LocalDateTime.of(2026, 6, 10, 12, 0), "Lunch",
				null, null, null, null), user);

		assertThat(cachedKeysOf("insightMonthSummary")).isEmpty();
	}

	/** Update and delete evict too, and on the ADMIN path the owner's entries go, not the admin's. */
	@Test
	void anAdminEditingSomeoneElsesExpense_evictsTheOwnerNotTheAdmin() throws Exception {
		User admin = userRepository.save(User.builder()
				.name("Admin").email("cache-admin@test.com").role(Role.ADMIN)
				.password(passwordEncoder.encode("password")).build());
		var owned = expenseService.create(new ExpenseRequest(
				new BigDecimal("100.00"), "Food", LocalDateTime.now(), "Lunch", null, null, null, null), user);

		aiInsightService.getMonthSummary(user, "2026-06");
		aiInsightService.getMonthSummary(admin, "2026-06");
		assertThat(cachedKeysOf("insightMonthSummary")).hasSize(2);

		expenseService.update(owned.id(), new ExpenseRequest(
				new BigDecimal("120.00"), "Food", LocalDateTime.now(), "Lunch", null, null, null, null), admin);

		assertThat(cachedKeysOf("insightMonthSummary"))
				.noneMatch(key -> key.startsWith(user.getId() + "-"))
				.anyMatch(key -> key.startsWith(admin.getId() + "-"));

		aiInsightService.getMonthSummary(user, "2026-06");
		expenseService.delete(owned.id(), admin);
		assertThat(cachedKeysOf("insightMonthSummary"))
				.noneMatch(key -> key.startsWith(user.getId() + "-"))
				.anyMatch(key -> key.startsWith(admin.getId() + "-"));
	}

	@Test
	void deleteAll_evictsOnlyTheCallersInsights() throws Exception {
		User other = userRepository.save(User.builder()
				.name("Other User").email("cache-other@test.com")
				.password(passwordEncoder.encode("password")).build());
		aiInsightService.getMonthSummary(user, "2026-06");
		aiInsightService.getMonthSummary(other, "2026-06");

		expenseService.deleteAll(other);

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
