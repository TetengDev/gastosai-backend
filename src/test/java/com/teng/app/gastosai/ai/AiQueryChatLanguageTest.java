package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.ai.query.AnalyticsQueryPlanner;
import com.teng.app.gastosai.ai.query.GuardedFallbackExecutor;
import com.teng.app.gastosai.ai.query.QueryIntentValidator;
import com.teng.app.gastosai.ai.query.SafeAnalyticsExecutor;
import com.teng.app.gastosai.config.AiLanguageProperties;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AiRedactionService;
import com.teng.app.gastosai.service.AiUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@code /ai/query} must summarize in the user's {@code chatLanguage}, independently of the insight
 * language. Lives in the {@code ai} package so it can assert on the persona the adapter actually
 * builds, rather than only on the value handed to the port.
 */
@ExtendWith(MockitoExtension.class)
class AiQueryChatLanguageTest {

	@Mock SqlGenerator sqlGenerator;
	@Mock GuardedFallbackExecutor guardedFallbackExecutor;
	@Mock QueryIntentValidator queryIntentValidator;
	@Mock AnalyticsQueryPlanner analyticsQueryPlanner;
	@Mock SafeAnalyticsExecutor safeAnalyticsExecutor;
	@Mock AiQuotaService aiQuotaService;
	@Mock AiUsageService aiUsageService;
	@Mock AiRedactionService aiRedactionService;
	@Mock AiManagedProperties aiManagedProperties;
	@Mock AiProviderProperties aiProviderProperties;
	@Mock OpenAiProperties openAiProperties;
	@Mock ClaudeProperties claudeProperties;
	@Spy AiLanguageRegistry languages = registry();
	@InjectMocks AiQueryService aiQueryService;

	private static AiLanguageRegistry registry() {
		AiLanguageProperties properties = new AiLanguageProperties();
		properties.setSupported(List.of(
				new AiLanguageProperties.Entry("en", "English"),
				new AiLanguageProperties.Entry("fil", "Filipino")));
		return new AiLanguageRegistry(properties);
	}

	@BeforeEach
	void setUp() {
		Mockito.lenient().when(aiRedactionService.redact(anyString())).thenAnswer(i -> i.getArgument(0));
		Mockito.lenient().when(aiManagedProperties.getMaxPromptChars()).thenReturn(8000);
		Mockito.lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
		Mockito.lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
	}

	@Test
	void personaCarriesTheUsersChatLanguage() {
		AiLanguage language = summaryLanguageFor(user("fil", null));

		assertThat(language.code()).isEqualTo("fil");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt("plain", language)).contains("Filipino");
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt("plain", language)).contains("Filipino");
	}

	@Test
	void unsetChatLanguageIsEnglish() {
		AiLanguage language = summaryLanguageFor(user(null, null));

		assertThat(language.code()).isEqualTo("en");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt("plain", language)).contains("English");
	}

	/** The two settings stay independent: an insight language does not reach the assistant. */
	@Test
	void insightLanguageDoesNotChangeTheChatLanguage() {
		assertThat(summaryLanguageFor(user(null, "fil")).code()).isEqualTo("en");
	}

	private AiLanguage summaryLanguageFor(User user) {
		when(sqlGenerator.classifyQueryIntentJson(anyString())).thenReturn(LlmResult.ofValue(null));
		when(sqlGenerator.generateSql(anyString()))
				.thenReturn(LlmResult.ofValue("SELECT SUM(amount) FROM expenses"));
		when(guardedFallbackExecutor.run(anyString()))
				.thenReturn(List.of(Map.of("sum", new BigDecimal("10.00"))));
		ArgumentCaptor<AiLanguage> captor = ArgumentCaptor.forClass(AiLanguage.class);
		when(sqlGenerator.generateSummary(any(), any(), any(), captor.capture()))
				.thenReturn(LlmResult.ofValue("ok"));

		aiQueryService.runNaturalLanguageQuery("magkano nagastos ko", "plain", user);

		return captor.getValue();
	}

	private User user(String chatLanguage, String insightLanguage) {
		return User.builder()
				.id(42L).role(Role.USER)
				.email("u@b.com").name("U").password("x")
				.chatLanguage(chatLanguage).insightLanguage(insightLanguage)
				.build();
	}
}
