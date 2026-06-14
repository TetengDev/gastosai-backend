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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user fixed-window rate limiter for the AI endpoints, which fan out to paid LLM providers and
 * are the most abusable surface. In-memory (single-instance) by design — a clean seam to swap for a
 * shared store (Redis/Bucket4j) later without touching callers. Returns 429 when the window is full.
 */
@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int requestsPerMinute;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public AiRateLimitInterceptor(@Value("${gastos.ratelimit.ai-per-minute:20}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user) || user.getId() == null) {
            return true;
        }
        if (allow(user.getId())) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"detail\":\"AI request rate limit exceeded. Please slow down.\"}");
        return false;
    }

    private boolean allow(long userId) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(userId, key -> new Window(now));
        synchronized (window) {
            if (now - window.start >= WINDOW_MILLIS) {
                window.start = now;
                window.count = 0;
            }
            if (window.count >= requestsPerMinute) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    private static final class Window {
        private long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
