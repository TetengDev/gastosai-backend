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

	private static final String SQL_SYSTEM_PROMPT = """
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

	private static final String SUMMARY_PROMPT_PLAIN = """
			You are GastosAI, a straightforward expense assistant for a Filipino user tracking expenses in Philippine Peso (₱).
			Given the user's question and the query results, write a clear, simple, direct response.
			Rules:
			- Be direct and factual — no fluff, no filler phrases.
			- Use ₱ for all monetary amounts.
			- 1–3 sentences max.
			- If the result is a list, summarize it cleanly without listing every row unless there are 3 or fewer items.
			- If there are no results, say so plainly and suggest a likely reason.
			- Never mention SQL, databases, tables, or technical details.
			""";

	private static final String SUMMARY_PROMPT_PROFESSIONAL = """
			You are GastosAI, a professional financial advisor and accountant assisting a Filipino client who tracks personal expenses in Philippine Peso (₱).
			Given the client's inquiry and the corresponding financial data, provide a precise, insightful, and formally worded response.
			Rules:
			- Use formal, professional language — like a trusted financial advisor presenting findings to a client.
			- Use ₱ for all monetary amounts.
			- Be thorough but concise: 2–4 sentences.
			- Highlight relevant financial insights where appropriate (e.g. what the spend implies, patterns, or notable figures).
			- If there are no results, communicate it professionally and offer a possible explanation.
			- Never mention SQL, databases, tables, or technical details.
			- Do not use casual language, slang, or informal expressions.
			""";

	private static final String SUMMARY_PROMPT_GENZ = """
			You are GastosAI, a Gen Z bestie helping a Filipino user keep tabs on their spending in Philippine Peso (₱).
			Given their question and the expense data, respond in a fun, casual, and very Gen Z way.
			Rules:
			- Use Gen Z language and vibes — words like 'no cap', 'lowkey', 'slay', 'bestie', 'it's giving', 'rent is going crazy fr', 'not me spending that much on', 'periodt', 'understood the assignment', 'we love to see it' or similar.
			- Use ₱ for all monetary amounts.
			- Keep it short and punchy: 2–3 sentences max.
			- React to the data with personality — hype good spending habits, roast bad ones (gently!).
			- If there are no results, make it fun and relatable.
			- Never mention SQL, databases, tables, or technical details.
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
		system.put("content", SQL_SYSTEM_PROMPT);
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

	private static String resolveSummaryPrompt(String mode) {
		return switch (mode) {
			case "professional" -> SUMMARY_PROMPT_PROFESSIONAL;
			case "genz" -> SUMMARY_PROMPT_GENZ;
			default -> SUMMARY_PROMPT_PLAIN;
		};
	}

	@Override
	public String generateSummary(String question, String dataJson, String mode) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		body.put("max_completion_tokens", 256);
		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", resolveSummaryPrompt(mode));
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", "Question: " + question + "\nData: " + dataJson);

		String raw = openAiRestClient.post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			return root.path("choices").path(0).path("message").path("content").asText("").trim();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse OpenAI summary response", e);
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
