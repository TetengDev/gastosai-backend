package com.teng.app.gastosai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class PublicRateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PublicRateLimitInterceptor(
            @Value("${gastos.ratelimit.public-per-minute:10}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String ip = clientIp(request);
        if (allow(ip)) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"detail\":\"Rate limit exceeded. Please slow down.\"}");
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(ip, key -> new Window(now));
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
