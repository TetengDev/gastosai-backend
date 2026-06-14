package com.teng.app.gastosai.ai.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Executes a parameterized {@link AnalyticsQueryPlan} read-only, with a statement timeout to bound
 * cost. This is the only place query intents reach the database; it accepts nothing but a plan whose
 * SQL is built from the allowlist, so no AI-authored SQL can run here.
 */
@Component
public class SafeAnalyticsExecutor {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SafeAnalyticsExecutor(DataSource dataSource) {
        JdbcTemplate base = new JdbcTemplate(dataSource);
        base.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.jdbcTemplate = new NamedParameterJdbcTemplate(base);
    }

    public List<Map<String, Object>> run(AnalyticsQueryPlan plan) {
        return jdbcTemplate.queryForList(plan.sql(), plan.parameters());
    }
}
