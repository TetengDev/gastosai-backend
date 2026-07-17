package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AppEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticatedWriteRateLimitInterceptor implements HandlerInterceptor {

    private final int requestsPerMinute;
    private final RateLimiterStore store;
    private final AppEventService appEventService;

    public AuthenticatedWriteRateLimitInterceptor(
            @Value("${gastos.ratelimit.write-per-minute:60}") int requestsPerMinute,
            RateLimiterStore store,
            AppEventService appEventService) {
        this.requestsPerMinute = requestsPerMinute;
        this.store = store;
        this.appEventService = appEventService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user) || user.getId() == null) {
            return true;
        }
        if (store.tryAcquire("write:" + user.getId(), requestsPerMinute)) {
            return true;
        }
        appEventService.recordAbuseTrip("WRITE_RATE_LIMIT", user.getId(), request.getRequestURI(),
                "Authenticated write rate limit exceeded");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Write rate limit exceeded. Please slow down.\"}");
        return false;
    }
}
