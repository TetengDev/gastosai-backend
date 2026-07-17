package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.AppEvent;

import java.time.LocalDateTime;

public record AppEventDto(
        Long id,
        String eventType,
        String severity,
        String requestId,
        Long userId,
        String path,
        Integer httpStatus,
        String message,
        LocalDateTime createdAt
) {
    public static AppEventDto from(AppEvent e) {
        return new AppEventDto(e.getId(), e.getEventType(), e.getSeverity(), e.getRequestId(),
                e.getUserId(), e.getPath(), e.getHttpStatus(), e.getMessage(), e.getCreatedAt());
    }
}
