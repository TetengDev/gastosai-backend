package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code paused} is a boxed {@code Boolean}, not a primitive, and that distinction is load-bearing.
 *
 * A record has no field defaults for Jackson to fall back on, so an absent primitive is bound as
 * null and deserialization fails with "Cannot map `null` into type `boolean`" — a 500 on what is
 * really a valid request. The published contract marks {@code paused} optional, so every client
 * that believed it was unable to create a goal at all.
 *
 * Read it through {@link #isPaused()} rather than the accessor, so absent and false stay the same
 * thing everywhere downstream.
 */
public record GoalRequest(
        @NotBlank String name,
        @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
        @NotNull @DecimalMin("0.00") BigDecimal savedAmount,
        LocalDate targetDate,
        Boolean paused,
        String currency
) {
    /** Absent means not paused — a newly created goal is active. */
    public boolean isPaused() {
        return Boolean.TRUE.equals(paused);
    }
}
