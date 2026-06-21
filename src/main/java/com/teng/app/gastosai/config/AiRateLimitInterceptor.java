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
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-user fixed-window rate limiter for the AI endpoints, which fan out to paid LLM providers and
 * are the most abusable surface. Delegates counting to a {@link RateLimiterStore} (in-memory by
 * default, Redis when enabled) so the limit holds across instances. Returns 429 when the window is full.
 */
@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

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
        if (store.tryAcquire("ai:" + user.getId(), requestsPerMinute)) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"detail\":\"AI request rate limit exceeded. Please slow down.\"}");
        return false;
    }
}
