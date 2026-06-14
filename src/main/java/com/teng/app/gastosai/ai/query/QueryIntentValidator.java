package com.teng.app.gastosai.ai.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Parses and validates the LLM-produced query-intent JSON against strict allowlists.
 * Anything unrecognized (unknown metric, range, sort) yields {@link Optional#empty()},
 * which signals the caller to fall back to the guarded NL-to-SQL path. The AI is never trusted:
 * unknown enum values are rejected rather than coerced, and {@code limit} is clamped.
 */
@Component
public class QueryIntentValidator {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CATEGORY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(QueryIntentValidator.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<QueryIntent> parse(String intentJson) {
        if (intentJson == null || intentJson.isBlank()) {
            return Optional.empty();
        }
        try {
            return validate(objectMapper.readTree(intentJson));
        } catch (Exception e) {
            log.warn("AI query intent JSON could not be parsed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<QueryIntent> validate(JsonNode node) {
        Optional<Metric> metric = Metric.fromString(text(node, "metric"));
        if (metric.isEmpty()) {
            log.info("Rejecting AI query intent: unknown or missing metric [{}]", text(node, "metric"));
            return Optional.empty();
        }

        // Absent optional fields default; present-but-unrecognized values are rejected (the AI is not
        // trusted to invent enum values — a garbled field should fall back, not run the wrong window).
        DateRange dateRange = DateRange.CURRENT_MONTH;
        if (has(node, "dateRange")) {
            Optional<DateRange> parsed = DateRange.fromString(text(node, "dateRange"));
            if (parsed.isEmpty()) {
                log.info("Rejecting AI query intent: unknown dateRange [{}]", text(node, "dateRange"));
                return Optional.empty();
            }
            dateRange = parsed.get();
        }

        SortDirection sort = SortDirection.DESC;
        if (has(node, "sort")) {
            Optional<SortDirection> parsed = SortDirection.fromString(text(node, "sort"));
            if (parsed.isEmpty()) {
                log.info("Rejecting AI query intent: unknown sort [{}]", text(node, "sort"));
                return Optional.empty();
            }
            sort = parsed.get();
        }

        String category = text(node, "category");
        if (category != null) {
            category = category.trim();
            if (category.isEmpty() || category.length() > MAX_CATEGORY_LENGTH) {
                category = null;
            }
        }

        int limit = node.path("limit").asInt(DEFAULT_LIMIT);
        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);

        return Optional.of(new QueryIntent(metric.get(), dateRange, category, sort, limit));
    }

    private static boolean has(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
