package com.teng.app.gastosai.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(AiStartupValidator.class);

    private final AiManagedProperties managedProps;
    private final AiProviderProperties providerProps;
    private final OpenAiProperties openAiProperties;
    private final ClaudeProperties claudeProperties;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!managedProps.isFeaturesEnabled()) {
            log.info("AI features are disabled (AI_FEATURES_ENABLED=false). All AI endpoints are inactive.");
            return;
        }
        if (!managedProps.isAllowSharedKey()) {
            return;
        }
        if (isTestEnvironment()) {
            return;
        }
        String provider = providerProps.getProvider();
        boolean keyMissing = "claude".equalsIgnoreCase(provider)
                ? (claudeProperties.getApiKey() == null || claudeProperties.getApiKey().isBlank())
                : (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank());
        if (keyMissing) {
            throw new IllegalStateException(
                    "AI managed mode is enabled (AI_ALLOW_SHARED_KEY=true) but the " + provider +
                    " API key is not configured. Set the key or disable managed mode.");
        }
    }

    private boolean isTestEnvironment() {
        return datasourceUrl != null && datasourceUrl.startsWith("jdbc:h2:");
    }
}
