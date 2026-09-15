package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiLanguage;
import com.teng.app.gastosai.ai.AiLanguageRegistry;
import com.teng.app.gastosai.config.AiLanguageProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately outside {@code com.teng.app.gastosai.ai}: this is the caller the allow-list has to
 * hold against. The code an {@link AiLanguage} carries reaches a prompt, so a caller in another
 * package must have no way to build one from user input — the registry is the only door, and it
 * rejects anything unconfigured.
 *
 * <p>The constructor check is by reflection because the compiler check cannot be written as a
 * passing test: {@code new AiLanguage(…)} here would simply not compile, which is the guarantee but
 * not an assertion. If someone widens the constructor, this fails and says why.
 */
class AiLanguageAllowListBoundaryTest {

	private AiLanguageRegistry registry() {
		AiLanguageProperties properties = new AiLanguageProperties();
		properties.setSupported(List.of(
				new AiLanguageProperties.Entry("en", "English"),
				new AiLanguageProperties.Entry("fil", "Filipino")));
		return new AiLanguageRegistry(properties);
	}

	@Test
	void noConstructorIsReachableFromOutsideTheAiPackage() {
		for (Constructor<?> constructor : AiLanguage.class.getDeclaredConstructors()) {
			int modifiers = constructor.getModifiers();
			assertThat(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
					.as("AiLanguage constructor %s must stay package-private: the registry is the "
							+ "allow-list, and a public constructor lets any caller hand an "
							+ "unvalidated code to promptInstruction()", constructor)
					.isFalse();
		}
	}

	@Test
	void theRegistryIsHowThisPackageObtainsOne() {
		AiLanguage filipino = registry().fromCode("fil");
		assertThat(filipino.code()).isEqualTo("fil");
		assertThat(filipino.promptInstruction()).contains("Filipino");
	}

	@Test
	void andItStillRefusesACodeNobodyConfigured() {
		assertThat(registry().fromCodeOrDefault("xx").code()).isEqualTo("en");
	}
}
