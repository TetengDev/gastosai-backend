package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.exception.AiQuotaExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Guards external LLM provider calls. Once the provider fails past the threshold the breaker opens
 * and calls fast-fail to the degraded fallback instead of hammering a down provider; it probes again
 * after a cool-off (HALF_OPEN). Any failure also returns the fallback, so a flaky provider degrades
 * gracefully rather than surfacing a 500.
 *
 * <p>{@link AiQuotaExceededException} is the one exception that does not degrade. It is a decision
 * this backend made about the caller — not a provider fault — so it is neither recorded in the
 * sliding window (capped users must not push the breaker toward OPEN for everybody else) nor
 * converted into the fallback: it propagates so {@code GlobalExceptionHandler} can answer 429 and
 * the user learns the cap is what stopped them.
 */
@Component
public class LlmCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    private final CircuitBreaker breaker;

    public LlmCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(AiQuotaExceededException.class)
                .build();
        this.breaker = CircuitBreaker.of("llm", config);
    }

    /**
     * Runs {@code action} through the breaker; returns {@code fallback} when the breaker is open or
     * the call fails. {@link AiQuotaExceededException} is rethrown instead.
     */
    public <T> T execute(Supplier<T> action, Supplier<T> fallback) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            log.warn("LLM circuit is open — returning degraded response without calling the provider");
            return fallback.get();
        } catch (AiQuotaExceededException e) {
            // Not a provider failure: the caller is over their cap and must be told so.
            throw e;
        } catch (RuntimeException e) {
            log.warn("LLM call failed ({}) — returning degraded response", e.getMessage());
            return fallback.get();
        }
    }
}
