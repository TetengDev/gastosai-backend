package com.teng.app.gastosai.ai.query;

import java.util.Map;

/**
 * A fully-built, parameterized read-only query. {@code sql} contains only named placeholders
 * (no interpolated values); {@code parameters} supplies every bound value, including the
 * mandatory {@code userId} scope.
 */
public record AnalyticsQueryPlan(String sql, Map<String, Object> parameters) {
}
