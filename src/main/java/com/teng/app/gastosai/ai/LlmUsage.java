package com.teng.app.gastosai.ai;

public record LlmUsage(Integer inputTokens, Integer outputTokens) {

    public static LlmUsage absent() {
        return new LlmUsage(null, null);
    }
}
