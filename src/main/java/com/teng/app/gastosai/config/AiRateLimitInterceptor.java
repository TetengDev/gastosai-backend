package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-user fixed-window rate limiter for the AI endpoints, which fan out to paid LLM providers and
 * are the most abusable surface. Delegates counting to a {@link RateLimiterStore} (in-memory by
 * default, Redis when enabled) so the limit holds across instances. Returns 429 when the window is full.
 *
 * <p><strong>This bounds rate, not concurrency.</strong> The window counts requests per minute and
 * says nothing about how many of them are in flight at the same instant, so one account inside its
 * own allowance can send the whole minute's worth simultaneously. That matters on {@code /ai/vision},
 * where each request decodes an attacker-sized image and the heap cost is a function of how many
 * decodes overlap. The concurrency bound for that is
 * {@code VisionService.runBoundedDecode} — a global and a per-user limit on decodes in flight — and
 * it is deliberately not here: this interceptor runs before the multipart body is read and has no
 * way to hold a slot across the handler.
 *
 * <p>The cached insight endpoints ({@code month-summary}, {@code recommendations}) are metered from
 * inside {@code AiInsightService} instead, on a cache miss only, so every model call still spends from
 * this window (TEN-483).
 *
 * <p><strong>This limiter is load-bearing for that gate.</strong> A decode refused for capacity
 * deliberately writes no {@code AiUsage} row — it read no bytes and decoded nothing, so charging it
 * against the caller's monthly cap would bill honest contention — which leaves the window below as
 * the only thing metering how often one caller may collide with the bound. Raising
 * {@code gastos.ratelimit.ai-per-minute} far above its default, or exempting a class of caller from
 * this interceptor, means revisiting that exemption: nothing else counts capacity refusals.
 */
@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

    static final String LIMIT_DETAIL = "AI request rate limit exceeded. Please slow down.";

    private final int requestsPerMinute;
    private final RateLimiterStore store;

    public AiRateLimitInterceptor(@Value("${gastos.ratelimit.ai-per-minute:20}") int requestsPerMinute,
                                  RateLimiterStore store) {
        this.requestsPerMinute = requestsPerMinute;
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user) || user.getId() == null) {
            return true;
        }
        if (tryAcquire(user)) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"detail\":\"" + LIMIT_DETAIL + "\"}");
        return false;
    }

    /**
     * Spends a token for a model call that the path-level gate does not cover: the cached insight
     * endpoints, whose cache hits make no model call and so must not spend one (TEN-483).
     */
    public void acquireOrThrow(User user) {
        if (!tryAcquire(user)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, LIMIT_DETAIL);
        }
    }

    private boolean tryAcquire(User user) {
        return store.tryAcquire("ai:" + user.getId(), requestsPerMinute);
    }
}
