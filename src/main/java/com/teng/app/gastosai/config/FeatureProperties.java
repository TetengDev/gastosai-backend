package com.teng.app.gastosai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.features")
public record FeatureProperties(boolean csvImport, boolean chatAttachments) {}