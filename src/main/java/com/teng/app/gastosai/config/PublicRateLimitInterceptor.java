package com.teng.app.gastosai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PublicRateLimitInterceptor implements HandlerInterceptor {

    private final int requestsPerMinute;
    private final RateLimiterStore store;

    public PublicRateLimitInterceptor(
            @Value("${gastos.ratelimit.public-per-minute:10}") int requestsPerMinute,
            RateLimiterStore store) {
        this.requestsPerMinute = requestsPerMinute;
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String ip = clientIp(request);
        if (store.tryAcquire("pub:" + ip, requestsPerMinute)) {
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
}
