package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.GoalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GoalResponse(
        Long id,
        String name,
        BigDecimal targetAmount,
        BigDecimal savedAmount,
        BigDecimal progressPercent,
        LocalDate targetDate,
        GoalStatus status,
        boolean paused,
        LocalDateTime createdAt,
        String currency
) {}
