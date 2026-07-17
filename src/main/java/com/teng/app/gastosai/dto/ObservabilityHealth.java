package com.teng.app.gastosai.dto;

public record ObservabilityHealth(
        boolean dbUp,
        String version,
        long uptimeSeconds
) {}
