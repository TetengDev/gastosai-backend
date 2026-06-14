package com.teng.app.gastosai.ai.query;

import java.util.Optional;

/**
 * Allowlisted analytics metrics. The metric encodes both the aggregation and any grouping,
 * so the AI never names a raw column or function — it only selects one of these values.
 */
public enum Metric {
    TOTAL(false),
    AVERAGE(false),
    COUNT(false),
    SUM_BY_CATEGORY(true),
    SUM_BY_DAY(true),
    SUM_BY_MONTH(true);

    private final boolean grouped;

    Metric(boolean grouped) {
        this.grouped = grouped;
    }

    /** True when the metric produces multiple rows (a breakdown) rather than a single scalar. */
    public boolean isGrouped() {
        return grouped;
    }

    public static Optional<Metric> fromString(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Metric.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
