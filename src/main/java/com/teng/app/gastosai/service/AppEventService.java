package com.teng.app.gastosai.service;

import com.teng.app.gastosai.entity.AppEvent;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AppEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists operational events (server errors, abuse-guard trips) to {@code app_event}
 * for the admin observability view. Recording runs in its own transaction and never
 * throws into the caller — a failed insert must not break the request it describes
 * (mirrors {@link AiUsageService#record}).
 */
@Service
@RequiredArgsConstructor
public class AppEventService {

    private static final Logger log = LoggerFactory.getLogger(AppEventService.class);

    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARN = "WARN";

    private static final int MESSAGE_MAX = 500;
    private static final int DETAIL_MAX = 20_000;

    private final AppEventRepository appEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String severity, Long userId, String path,
                       Integer httpStatus, String message, String detail) {
        AppEvent event = AppEvent.builder()
                .eventType(eventType)
                .severity(severity)
                .requestId(MDC.get("requestId"))
                .userId(userId)
                .path(path)
                .httpStatus(httpStatus)
                .message(truncate(message, MESSAGE_MAX))
                .detail(truncate(detail, DETAIL_MAX))
                .build();
        try {
            appEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to record app_event {}: {}", eventType, e.getMessage());
        }
    }

    /** Unhandled server error (5xx). {@code detail} carries the exception class + message. */
    public void recordError(String path, Integer httpStatus, String message, String detail) {
        record("SERVER_ERROR", SEVERITY_ERROR, currentUserId(), path, httpStatus, message, detail);
    }

    /** An abuse guard rejected a request (rate limit, signup cap, global AI budget). */
    public void recordAbuseTrip(String eventType, Long userId, String path, String message) {
        record(eventType, SEVERITY_WARN, userId, path, 429, message, null);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
