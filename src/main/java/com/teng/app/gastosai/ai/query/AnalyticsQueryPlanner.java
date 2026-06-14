package com.teng.app.gastosai.ai.query;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a validated {@link QueryIntent} into a parameterized, read-only SQL plan against the
 * {@code expenses} table. Every value — user scope, date bounds, category filter, limit — is bound
 * as a named parameter; the only AI-influenced inputs (metric, range, sort) select fixed SQL
 * fragments from an allowlist. No user text is ever concatenated into the SQL string.
 */
@Component
public class AnalyticsQueryPlanner {

    private static final String AMOUNT = "e.amount_in_base_currency";
    private static final String BASE = " FROM expenses e";
    private static final String CATEGORY_JOIN = " LEFT JOIN categories c ON c.id = e.category_id";

    private final Clock clock;

    public AnalyticsQueryPlanner() {
        this(Clock.systemDefaultZone());
    }

    AnalyticsQueryPlanner(Clock clock) {
        this.clock = clock;
    }

    public AnalyticsQueryPlan build(QueryIntent intent, long userId) {
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);

        boolean needsCategoryJoin = intent.metric() == Metric.SUM_BY_CATEGORY || intent.category() != null;

        StringBuilder where = new StringBuilder(" WHERE e.user_id = :userId");
        if (intent.dateRange() != DateRange.ALL) {
            params.put("startDate", intent.dateRange().startInclusive(today));
            params.put("endDate", intent.dateRange().endExclusive(today));
            where.append(" AND e.date >= :startDate AND e.date < :endDate");
        }
        if (intent.category() != null) {
            params.put("category", intent.category());
            where.append(" AND LOWER(c.name) = LOWER(:category)");
        }

        String join = needsCategoryJoin ? CATEGORY_JOIN : "";
        String sort = intent.sort().sql();

        String sql = switch (intent.metric()) {
            case TOTAL -> "SELECT COALESCE(SUM(" + AMOUNT + "), 0) AS total" + BASE + join + where;
            case AVERAGE -> "SELECT COALESCE(AVG(" + AMOUNT + "), 0) AS average" + BASE + join + where;
            case COUNT -> "SELECT COUNT(*) AS count" + BASE + join + where;
            case SUM_BY_CATEGORY -> "SELECT COALESCE(c.name, 'Uncategorized') AS category, COALESCE(SUM(" + AMOUNT + "), 0) AS total"
                    + BASE + join + where
                    + " GROUP BY c.name ORDER BY total " + sort + " LIMIT :limit";
            case SUM_BY_DAY -> "SELECT CAST(e.date AS DATE) AS day, COALESCE(SUM(" + AMOUNT + "), 0) AS total"
                    + BASE + join + where
                    + " GROUP BY CAST(e.date AS DATE) ORDER BY day " + sort + " LIMIT :limit";
            case SUM_BY_MONTH -> "SELECT date_trunc('month', e.date) AS month, COALESCE(SUM(" + AMOUNT + "), 0) AS total"
                    + BASE + join + where
                    + " GROUP BY date_trunc('month', e.date) ORDER BY month " + sort + " LIMIT :limit";
        };

        if (intent.metric().isGrouped()) {
            params.put("limit", intent.limit());
        }

        return new AnalyticsQueryPlan(sql, params);
    }
}
