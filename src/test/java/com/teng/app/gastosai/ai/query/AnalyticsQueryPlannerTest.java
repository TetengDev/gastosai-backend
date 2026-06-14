package com.teng.app.gastosai.ai.query;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsQueryPlannerTest {

    private static final long USER_ID = 42L;
    // Fixed "today" = 2026-06-15 in Manila.
    private final AnalyticsQueryPlanner planner =
            new AnalyticsQueryPlanner(Clock.fixed(Instant.parse("2026-06-15T08:00:00Z"), ZoneId.of("Asia/Manila")));

    @Test
    void totalCurrentMonth_isScalarScopedAndDateBounded() {
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.TOTAL, DateRange.CURRENT_MONTH, null, SortDirection.DESC, 10), USER_ID);

        assertThat(plan.sql())
                .contains("SUM(e.amount_in_base_currency)")
                .contains("AS total")
                .contains("e.user_id = :userId")
                .contains("e.date >= :startDate AND e.date < :endDate")
                .doesNotContain("LIMIT");
        assertThat(plan.parameters())
                .containsEntry("userId", USER_ID)
                .containsEntry("startDate", LocalDateTime.parse("2026-06-01T00:00:00"))
                .containsEntry("endDate", LocalDateTime.parse("2026-07-01T00:00:00"))
                .doesNotContainKey("limit");
    }

    @Test
    void sumByCategory_groupsOrdersAndLimits() {
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.SUM_BY_CATEGORY, DateRange.CURRENT_MONTH, null, SortDirection.DESC, 5), USER_ID);

        assertThat(plan.sql())
                .contains("LEFT JOIN categories c ON c.id = e.category_id")
                .contains("GROUP BY c.name")
                .contains("ORDER BY total DESC")
                .contains("LIMIT :limit");
        assertThat(plan.parameters()).containsEntry("limit", 5);
    }

    @Test
    void categoryFilter_isParameterizedNotInterpolated() {
        String injection = "Food'; DROP TABLE expenses;--";
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.TOTAL, DateRange.ALL, injection, SortDirection.DESC, 10), USER_ID);

        assertThat(plan.sql())
                .contains("LOWER(c.name) = LOWER(:category)")
                .doesNotContain("DROP TABLE");
        // The raw (even malicious) value travels only as a bound parameter.
        assertThat(plan.parameters()).containsEntry("category", injection);
    }

    @Test
    void allRange_hasNoDatePredicate() {
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.COUNT, DateRange.ALL, null, SortDirection.DESC, 10), USER_ID);

        assertThat(plan.sql()).contains("COUNT(*)").doesNotContain(":startDate");
        assertThat(plan.parameters()).containsOnlyKeys("userId");
    }

    @Test
    void sumByMonth_usesDateTrunc() {
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.SUM_BY_MONTH, DateRange.YEAR_TO_DATE, null, SortDirection.ASC, 12), USER_ID);

        assertThat(plan.sql()).contains("date_trunc('month', e.date)").contains("ORDER BY month ASC");
    }
}
