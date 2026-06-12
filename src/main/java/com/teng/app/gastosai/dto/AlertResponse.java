package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;

import java.time.LocalDateTime;

public record AlertResponse(
        Long id,
        AlertType type,
        AlertSeverity severity,
        String month,
        String categoryName,
        String message,
        boolean read,
        boolean dismissed,
        LocalDateTime createdAt
) {}
