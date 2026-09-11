package com.teng.app.gastosai.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every prompt that produces user-facing prose must name its language outright. A prompt that
 * names only the audience ("for Filipino users") leaves the model to pick one per call, which is
 * the bug behind TEN-379.
 */
class AiPromptLanguageTest {

	@ParameterizedTest
	@ValueSource(strings = {"plain", "professional", "genz"})
	void everyChatPersona_statesTheLanguage(String mode) {
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.FIL)).contains("Filipino (Tagalog)");
		assertThat(ClaudeSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.EN)).contains("English");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.FIL)).contains("Filipino (Tagalog)");
		assertThat(OpenAiSqlGenerator.resolveSummaryPrompt(mode, AiLanguage.EN)).contains("English");
	}

	@ParameterizedTest
	@ValueSource(strings = {"month-summary", "recommendations"})
	void bothInsightPrompts_stateTheLanguage(String insightType) {
		assertThat(ClaudeSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.FIL)).contains("Filipino (Tagalog)");
		assertThat(ClaudeSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.EN)).contains("English");
		assertThat(OpenAiSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.FIL)).contains("Filipino (Tagalog)");
		assertThat(OpenAiSqlGenerator.resolveInsightPrompt(insightType, AiLanguage.EN)).contains("English");
	}

	@Test
	void personaAndPhilippineContext_surviveTheLanguageInstruction() {
		String genz = ClaudeSqlGenerator.resolveSummaryPrompt("genz", AiLanguage.EN);
		assertThat(genz).contains("Gen Z").contains("₱").contains("Filipino user");

		String professional = OpenAiSqlGenerator.resolveSummaryPrompt("professional", AiLanguage.FIL);
		assertThat(professional).contains("financial advisor").contains("Philippine Peso (₱)");
	}

	@Test
	void recommendationsPrompt_keepsItsJsonArrayContract() {
		String prompt = OpenAiSqlGenerator.resolveInsightPrompt("recommendations", AiLanguage.FIL);
		assertThat(prompt).contains("JSON array").contains("Return only the JSON array");
	}
}
