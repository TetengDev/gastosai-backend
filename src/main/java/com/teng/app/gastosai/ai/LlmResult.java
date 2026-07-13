package com.teng.app.gastosai.ai;

public record LlmResult<T>(T value, LlmUsage usage) {

    public static <T> LlmResult<T> of(T value, LlmUsage usage) {
        return new LlmResult<>(value, usage);
    }

    public static <T> LlmResult<T> ofValue(T value) {
        return new LlmResult<>(value, LlmUsage.absent());
    }
}
