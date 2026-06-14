package com.teng.app.gastosai.ai.query;

/**
 * A validated analytics request. Every field is constrained to an allowlisted value by
 * {@link QueryIntentValidator} before this record is constructed, so it is safe to translate
 * directly into a parameterized query. {@code category} is an optional user-supplied filter
 * value (always bound as a parameter, never interpolated).
 */
public record QueryIntent(
        Metric metric,
        DateRange dateRange,
        String category,
        SortDirection sort,
        int limit
) {
}
