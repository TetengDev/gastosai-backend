package com.teng.app.gastosai.ai.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Allowlisted relative date windows. Concrete bounds are computed server-side in wall-clock
 * local time (consistent with how expense timestamps are stored) and bound as query parameters,
 * so the AI never supplies a literal date.
 */
public enum DateRange {
    CURRENT_MONTH,
    LAST_MONTH,
    LAST_3_MONTHS,
    YEAR_TO_DATE,
    ALL;

    /** Inclusive lower bound, or {@code null} for {@link #ALL} (no lower bound). */
    public LocalDateTime startInclusive(LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return switch (this) {
            case CURRENT_MONTH -> firstOfMonth.atStartOfDay();
            case LAST_MONTH -> firstOfMonth.minusMonths(1).atStartOfDay();
            case LAST_3_MONTHS -> firstOfMonth.minusMonths(2).atStartOfDay();
            case YEAR_TO_DATE -> today.withDayOfYear(1).atStartOfDay();
            case ALL -> null;
        };
    }

    /** Exclusive upper bound, or {@code null} for {@link #ALL} (no upper bound). */
    public LocalDateTime endExclusive(LocalDate today) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return switch (this) {
            case CURRENT_MONTH, LAST_3_MONTHS, YEAR_TO_DATE -> firstOfMonth.plusMonths(1).atStartOfDay();
            case LAST_MONTH -> firstOfMonth.atStartOfDay();
            case ALL -> null;
        };
    }

    public static Optional<DateRange> fromString(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(DateRange.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
