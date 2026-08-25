package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.LlmCircuitBreaker;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rethrowsQuotaExceeded_insteadOfDegrading() {
        // The 429 has to reach the client; the degraded 200 would tell the user the wrong thing.
        assertThatThrownBy(() -> breaker.execute(
                () -> { throw new AiQuotaExceededException(); },
                () -> "fallback"))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void quotaExceeded_doesNotCountAsAProviderFailure() {
        AtomicInteger calls = new AtomicInteger();

        // Well past minimumNumberOfCalls=5 at a 50% threshold: if these were recorded as failures
        // the breaker would be OPEN by now.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> breaker.execute(
                    () -> { throw new AiQuotaExceededException(); },
                    () -> "fallback"))
                    .isInstanceOf(AiQuotaExceededException.class);
        }

        String result = breaker.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        }, () -> "fallback");

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).as("the provider is still being called").isEqualTo(1);
    }
}
