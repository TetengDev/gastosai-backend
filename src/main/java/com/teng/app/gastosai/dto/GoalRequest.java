package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GoalRequest(
        @NotBlank String name,
        @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
        @NotNull @DecimalMin("0.00") BigDecimal savedAmount,
        LocalDate targetDate,
        boolean paused
) {}
