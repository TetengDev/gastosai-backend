package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.ai.SqlGuard;
import com.teng.app.gastosai.ai.query.AnalyticsQueryPlan;
import com.teng.app.gastosai.ai.query.AnalyticsQueryPlanner;
import com.teng.app.gastosai.ai.query.QueryIntent;
import com.teng.app.gastosai.ai.query.QueryIntentValidator;
import com.teng.app.gastosai.ai.query.SafeAnalyticsExecutor;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.entity.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiQueryService {

	private static final Logger log = LoggerFactory.getLogger(AiQueryService.class);
	private static final String DEFAULT_MODE = "plain";
	private static final String EXECUTION_FAILURE_MESSAGE =
			"I couldn't run that query right now. Try rephrasing — for example: 'total spent this month' or 'expenses by category this month'.";

	private final SqlGenerator sqlGenerator;
	private final JdbcTemplate jdbcTemplate;
	private final QueryIntentValidator queryIntentValidator;
	private final AnalyticsQueryPlanner analyticsQueryPlanner;
	private final SafeAnalyticsExecutor safeAnalyticsExecutor;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AiQueryResponse runNaturalLanguageQuery(String question, String mode, User user) {
		String resolvedMode = (mode != null && !mode.isBlank()) ? mode : DEFAULT_MODE;

		List<Map<String, Object>> rows = runStructuredQuery(question, user);
		if (rows == null) {
			rows = runGeneratedSql(question, user);
		}
		if (rows == null) {
			return new AiQueryResponse(EXECUTION_FAILURE_MESSAGE);
		}
		return summarize(question, rows, resolvedMode);
	}

	/**
	 * Preferred path: the LLM emits a structured intent which the server validates and turns into a
	 * fully parameterized, user-scoped query. Returns {@code null} when no valid intent is produced
	 * or execution fails, signalling the caller to fall back to the guarded NL-to-SQL path.
	 * Admins keep the legacy unscoped path so cross-user analytics still works for them.
	 */
	private List<Map<String, Object>> runStructuredQuery(String question, User user) {
		if (user.getId() == null) {
			return null;
		}
		String intentJson = sqlGenerator.classifyQueryIntentJson(question);
		if (intentJson == null || intentJson.isBlank()) {
			return null;
		}
		Optional<QueryIntent> intent = queryIntentValidator.parse(intentJson);
		if (intent.isEmpty()) {
			return null;
		}
		AnalyticsQueryPlan plan = analyticsQueryPlanner.build(intent.get(), user.getId());
		log.info("AI structured analytics: metric={} range={} (user {})",
				intent.get().metric(), intent.get().dateRange(), user.getId());
		try {
			return safeAnalyticsExecutor.run(plan);
		} catch (DataAccessException e) {
			log.warn("Structured analytics execution failed, falling back: {}", e.getMessage());
			return null;
		}
	}

	/** Fallback path: AI-authored SQL validated by {@link SqlGuard} and always user-scoped before execution. */
	private List<Map<String, Object>> runGeneratedSql(String question, User user) {
		if (user.getId() == null) {
			return null;
		}
		String rawSql = sqlGenerator.generateSql(question);
		String sql = SqlGuard.validateAndNormalize(rawSql);
		// Always scope to the requesting user — including admins. Running unscoped AI-authored SQL for
		// admins would expose every user's financial data; cross-user analytics, if ever needed, must be
		// a separate, deliberately-audited endpoint rather than this NL path.
		String scopedSql = appendUserFilter(sql, user.getId());
		// SQL may embed user identifiers / value literals — keep it at DEBUG, not INFO.
		log.debug("AI-generated SQL (user-scoped): {}", scopedSql);
		try {
			return jdbcTemplate.queryForList(scopedSql);
		} catch (DataAccessException e) {
			log.warn("AI query execution failed: {}", e.getMessage());
			log.debug("Failed SQL: {}", scopedSql);
			return null;
		}
	}

	private AiQueryResponse summarize(String question, List<Map<String, Object>> rows, String mode) {
		Object normalizedData = normalizeAnswer(rows);
		try {
			String dataJson = objectMapper.writeValueAsString(normalizedData);
			String summary = sqlGenerator.generateSummary(question, dataJson, mode);
			return new AiQueryResponse(summary);
		}
		catch (JsonProcessingException e) {
			log.warn("Failed to serialize query results for summary, returning raw data", e);
			return new AiQueryResponse(normalizedData);
		}
		catch (Exception e) {
			log.warn("Summary generation failed, returning raw data: {}", e.getMessage());
			return new AiQueryResponse(normalizedData);
		}
	}

	private static String appendUserFilter(String sql, Long userId) {
		String s = sql.trim().replaceAll("\\s+", " ");
		String lower = s.toLowerCase();
		String filter = " AND user_id = " + userId;

		int insertAt = s.length();
		for (String kw : List.of(" group by ", " order by ", " limit ", " having ")) {
			int idx = lower.lastIndexOf(kw);
			if (idx > 0 && idx < insertAt) insertAt = idx;
		}

		String before = s.substring(0, insertAt);
		String after = s.substring(insertAt);

		if (lower.substring(0, insertAt).contains(" where ")) {
			return before + filter + after;
		}
		return before + " WHERE user_id = " + userId + after;
	}

	private static Object normalizeAnswer(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return null;
		}
		if (rows.size() == 1) {
			Map<String, Object> row = rows.getFirst();
			if (row.size() == 1) {
				Object value = row.values().iterator().next();
				return formatValue(value);
			}
		}
		return rows.stream()
				.map(row -> {
					Map<String, Object> formattedRow = new HashMap<>();
					row.forEach((key, value) -> formattedRow.put(key, formatValue(value)));
					return formattedRow;
				})
				.toList();
	}

	private static Object formatValue(Object value) {
		if (value instanceof BigDecimal newValue) {
			return newValue.setScale(2, RoundingMode.HALF_UP);
		}
		return value;
	}
}
