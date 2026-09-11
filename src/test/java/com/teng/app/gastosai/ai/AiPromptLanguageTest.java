package com.teng.app.gastosai.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every prompt that produces user-facing prose must name its language outright. A prompt that
 * names only the audience ("for Filipino users") leaves the model to pick one per call, which is
 * the bug behind TEN-379.
 *
 * <p>Japanese appears here on purpose: it was never an enum constant, so it is the case that would
 * have been unreachable before the language set became configuration.
 */
class AiPromptLanguageTest {

	private static final AiLanguage FILIPINO = new AiLanguage("fil", "Filipino");
	private static final AiLanguage JAPANESE = new AiLanguage("ja", "日本語");

	@ParameterizedTest
	@ValueSource(strings = {"plain", "professional", "genz"})
	void everyChatPersona_statesTheLanguage(String mode) {
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt(mode, FILIPINO)).contains("Filipino");
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.DEFAULT)).contains("English");
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt(mode, JAPANESE)).contains("日本語");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt(mode, FILIPINO)).contains("Filipino");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.DEFAULT)).contains("English");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt(mode, JAPANESE)).contains("日本語");
	}

	@ParameterizedTest
	@ValueSource(strings = {"month-summary", "recommendations"})
	void bothInsightPrompts_stateTheLanguage(String insightType) {
		assertThat(ClaudeSqlGenerator.resolveInsightPrompt(insightType, FILIPINO)).contains("Filipino");
		assertThat(ClaudeSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.DEFAULT)).contains("English");
		assertThat(ClaudeSqlGenerator.resolveInsightPrompt(insightType, JAPANESE)).contains("日本語");
		assertThat(OpenAiSqlGenerator.resolveInsightPrompt(insightType, FILIPINO)).contains("Filipino");
		assertThat(OpenAiSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.DEFAULT)).contains("English");
		assertThat(OpenAiSqlGenerator.resolveInsightPrompt(insightType, JAPANESE)).contains("日本語");
	}

	@Test
	void personaAndPhilippineContext_surviveTheLanguageInstruction() {
		String genz = ClaudeSqlGenerator.resolveSummaryPrompt("genz", AiLanguage.DEFAULT);
		assertThat(genz).contains("Gen Z").contains("₱").contains("Filipino user");

		String professional = OpenAiSqlGenerator.resolveSummaryPrompt("professional", FILIPINO);
		assertThat(professional).contains("financial advisor").contains("Philippine Peso (₱)");
	}

	@Test
	void recommendationsPrompt_keepsItsJsonArrayContract() {
		String prompt = OpenAiSqlGenerator.resolveInsightPrompt("recommendations", JAPANESE);
		assertThat(prompt).contains("JSON array").contains("Return only the JSON array");
	}
}
