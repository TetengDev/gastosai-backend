package com.teng.app.gastosai.dto;

public record AiSettingsResponse(boolean openaiKeySet, boolean claudeKeySet, boolean aiAvailable) {
}
