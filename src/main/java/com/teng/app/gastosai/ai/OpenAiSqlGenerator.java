package com.teng.app.gastosai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class OpenAiSqlGenerator implements SqlGenerator {

	private static final String SYSTEM_PROMPT = """
			You generate exactly one PostgreSQL SELECT query for the table "expenses".
			The schema is:
			- expenses(id bigint, amount numeric, category_id bigint, date timestamp, description text)
			- categories(id bigint, name varchar)

			When you need the category name, join:
			LEFT JOIN categories c ON c.id = e.category_id
			and select from `c.name`.
			Rules:
			- Output only the SQL, no markdown unless you wrap it in a single ```sql code block.
			- SELECT only; no semicolons at the end.
			- The FROM clause must include the `expenses` table (aliases like e are fine). Joins to `categories` are allowed.
			- Use standard PostgreSQL date functions when the user asks about months or ranges.
			""";

	private static final Pattern SQL_FENCE = Pattern.compile("(?is)```(?:sql)?\\s*([\\s\\S]*?)```");

	private final RestClient openAiRestClient;
	private final OpenAiProperties openAiProperties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public String generateSql(String question) {
		if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY is not configured");
		}

		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", SYSTEM_PROMPT);
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", question);

		String raw = openAiRestClient.post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		if (raw == null || raw.isBlank()) {
			throw new IllegalStateException("Empty response from OpenAI");
		}

		try {
			JsonNode root = objectMapper.readTree(raw);
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			return extractSql(content);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse OpenAI response", e);
		}
	}

	static String extractSql(String content) {
		if (content == null) {
			return "";
		}
		String t = content.trim();
		Matcher m = SQL_FENCE.matcher(t);
		if (m.find()) {
			return m.group(1).trim();
		}
		return t;
	}
}
