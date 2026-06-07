package com.teng.app.gastosai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.ClaudeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ClaudeSqlGenerator implements SqlGenerator {

	private static final String SYSTEM_PROMPT = """
			You generate exactly one PostgreSQL SELECT query for the table "expenses".
			The schema is:
			- expenses(id bigint, amount numeric, category_id bigint, date date, note text)
			- categories(id bigint, name varchar)

			When you need the category name, join:
			LEFT JOIN categories c ON c.id = e.category_id
			and select from c.name.
			Rules:
			- Output only the SQL, no markdown unless you wrap it in a single ```sql code block.
			- SELECT only; no semicolons at the end.
			- The FROM clause must include the "expenses" table (aliases like e are fine). Joins to "categories" are allowed.
			- Use standard PostgreSQL date functions when the user asks about months or ranges.
			""";

	private static final Pattern SQL_FENCE = Pattern.compile("(?is)```(?:sql)?\\s*([\\s\\S]*?)```");

	private final RestClient claudeRestClient;
	private final ClaudeProperties claudeProperties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public String generateSql(String question) {
		if (claudeProperties.getApiKey() == null || claudeProperties.getApiKey().isBlank()) {
			throw new IllegalStateException("CLAUDE_API_KEY is not configured");
		}

		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 1024);
		ArrayNode messages = body.putArray("messages");
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", SYSTEM_PROMPT + "\n\nQuestion: " + question);

		String raw = claudeRestClient.post()
				.uri("/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		if (raw == null || raw.isBlank()) {
			throw new IllegalStateException("Empty response from Claude");
		}

		try {
			JsonNode root = objectMapper.readTree(raw);
			String content = root.path("content").path(0).path("text").asText("");
			return extractSql(content);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude response", e);
		}
	}

	static String extractSql(String content) {
		if (content == null) return "";
		String t = content.trim();
		Matcher m = SQL_FENCE.matcher(t);
		return m.find() ? m.group(1).trim() : t;
	}
}
