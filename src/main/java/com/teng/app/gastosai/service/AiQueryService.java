package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.ai.SqlGuard;
import com.teng.app.gastosai.dto.AiQueryResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiQueryService {

	private static final Logger log = LoggerFactory.getLogger(AiQueryService.class);

	private final SqlGenerator sqlGenerator;
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AiQueryResponse runNaturalLanguageQuery(String question) {
		String rawSql = sqlGenerator.generateSql(question);
		String sql = SqlGuard.validateAndNormalize(rawSql);
		log.info("AI-generated SQL (validated): {}", sql);

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
		Object normalizedData = normalizeAnswer(rows);

		try {
			String dataJson = objectMapper.writeValueAsString(normalizedData);
			String summary = sqlGenerator.generateSummary(question, dataJson);
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
