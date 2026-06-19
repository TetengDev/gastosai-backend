package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.AiStartupValidator;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiStartupValidatorTest {

    private AiStartupValidator validator(boolean featuresEnabled, boolean allowSharedKey,
                                          String provider, String openAiKey, String claudeKey) {
        AiManagedProperties managed = new AiManagedProperties();
        managed.setFeaturesEnabled(featuresEnabled);
        managed.setAllowSharedKey(allowSharedKey);

        AiProviderProperties providerProps = new AiProviderProperties();
        providerProps.setProvider(provider);

        OpenAiProperties openAi = new OpenAiProperties();
        openAi.setApiKey(openAiKey);

        ClaudeProperties claude = new ClaudeProperties();
        claude.setApiKey(claudeKey);

        return new AiStartupValidator(managed, providerProps, openAi, claude);
    }

    @Test
    void managedOnAndKeyMissing_throwsOnReady() {
        AiStartupValidator v = validator(true, true, "openai", "", "");
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key is not configured");
    }

    @Test
    void managedOnAndKeyPresent_doesNotThrow() {
        AiStartupValidator v = validator(true, true, "openai", "sk-test-key", "");
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void managedOff_doesNotThrowEvenWithoutKey() {
        AiStartupValidator v = validator(true, false, "openai", "", "");
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void featuresDisabled_doesNotThrowEvenWithMissingKey() {
        AiStartupValidator v = validator(false, true, "openai", "", "");
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void claudeProvider_missingClaudeKey_throws() {
        AiStartupValidator v = validator(true, true, "claude", "sk-openai-key", "");
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude");
    }
}
