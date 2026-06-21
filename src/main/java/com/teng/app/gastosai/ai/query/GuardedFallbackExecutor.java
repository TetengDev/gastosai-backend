package com.teng.app.gastosai.ai.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Executes the SqlGuard-validated, user-scoped NL→SQL <em>fallback</em> query with a statement
 * timeout to bound cost. Unlike {@link SafeAnalyticsExecutor} (structured path), the SQL here is
 * AI-authored — it has already passed {@code SqlGuard} and has a {@code user_id} filter appended by
 * {@code AiQueryService.appendUserFilter} before reaching this point. The timeout matches the
 * structured executor so neither AI DB path can run unbounded.
 */
@Component
public class GuardedFallbackExecutor {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate jdbcTemplate;

    public GuardedFallbackExecutor(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.jdbcTemplate = template;
    }

    public List<Map<String, Object>> run(String sql) {
        return jdbcTemplate.queryForList(sql);
    }
}
