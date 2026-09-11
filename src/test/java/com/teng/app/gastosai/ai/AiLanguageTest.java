package com.teng.app.gastosai.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value type only. Resolution and the allow-list moved to {@link AiLanguageRegistry} and are
 * covered by {@link AiLanguageRegistryTest}; what is left here is the prompt wording, which is the
 * part a reviewer of a prompt change should see fail.
 */
class AiLanguageTest {

	@Test
	void theDefaultIsEnglish() {
		assertThat(AiLanguage.DEFAULT.code()).isEqualTo(AiLanguage.DEFAULT_CODE).isEqualTo("en");
		assertThat(AiLanguage.DEFAULT.displayName()).isEqualTo("English");
	}

	@Test
	void promptInstruction_namesTheLanguageAndProtectsPesoAndJson() {
		assertThat(new AiLanguage("fil", "Filipino").promptInstruction())
				.contains("Filipino")
				.contains("₱")
				.contains("JSON");
		assertThat(AiLanguage.DEFAULT.promptInstruction()).contains("English");
	}

	@Test
	void promptInstruction_namesALanguageThatNeverExistedAsAnEnumConstant() {
		// The point of the configuration-driven registry: a language nobody wrote Java for still
		// reaches the prompt by name.
		assertThat(new AiLanguage("ja", "日本語").promptInstruction()).contains("日本語");
	}
}
