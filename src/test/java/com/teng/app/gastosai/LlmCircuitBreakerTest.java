package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCircuitBreakerTest {

    private final LlmCircuitBreaker breaker = new LlmCircuitBreaker();

    @Test
    void returnsActionResultOnSuccess() {
        String result = breaker.execute(() -> "ok", () -> "fallback");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void returnsFallbackWhenActionThrows() {
        String result = breaker.execute(() -> { throw new IllegalStateException("provider down"); }, () -> "fallback");
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void opensAfterRepeatedFailures_thenStopsCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<String> failing = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider down");
        };

        // Drive enough failures to trip the breaker (minimumNumberOfCalls=5, threshold 50%).
        for (int i = 0; i < 10; i++) {
            assertThat(breaker.execute(failing, () -> "fallback")).isEqualTo("fallback");
        }
        int callsBeforeOpen = calls.get();

        // Once open, further requests fast-fail to the fallback without invoking the provider.
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.execute(failing, () -> "fallback")).isEqualTo("fallback");
        }
        assertThat(calls.get()).isEqualTo(callsBeforeOpen);
    }
}
