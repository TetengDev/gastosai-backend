package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.ai")
@Getter
@Setter
public class AiManagedProperties {

    private boolean featuresEnabled = true;
    private int requestTimeoutSeconds = 30;
    private int maxPromptChars = 8000;
    private boolean allowSharedKey = false;

    private int quotaFree = 30;
    private int quotaPremium = 300;
    private int quotaTrial = 50;

    private int visionFree = 5;
    private int visionPremium = 50;
    private int visionTrial = 10;
}
