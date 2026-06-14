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

	private static final String SQL_SYSTEM_PROMPT = """
			You generate exactly one PostgreSQL SELECT query for the table "expenses".
			The schema is:
			- expenses(id bigint, amount numeric, category_id bigint, date timestamp, description text)
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

	private static final String INSIGHT_SUMMARY_PROMPT =
			"You are GastosAI, a personal finance assistant for Filipino users. Given a JSON object with monthly expense data, write a 2–4 sentence natural-language summary of the user's spending. Use ₱ for currency. Do not mention SQL, databases, or technical terms. Keep the tone helpful and personal.";

	private static final String RECOMMENDATIONS_PROMPT =
			"You are GastosAI, a personal finance assistant for Filipino users. Given a JSON object with monthly expense data, return a JSON array of exactly 2–3 short, actionable spending recommendations as plain-text strings. Example: [\"Consider reducing Food spending which took 40% of your budget.\",\"Your Transport costs rose 20% vs last month.\"]. Use ₱ for currency. Return only the JSON array, no other text.";

	private static final String TOOL_DEFINITIONS = """
			[
			  {"name":"create_expense","description":"Create a new expense","input_schema":{"type":"object","properties":{"amount":{"type":"number"},"category":{"type":"string"},"description":{"type":"string"},"date":{"type":"string","description":"ISO date"}},"required":["amount","description"]}},
			  {"name":"update_expense","description":"Update an existing expense by id","input_schema":{"type":"object","properties":{"id":{"type":"number"},"amount":{"type":"number"},"category":{"type":"string"},"description":{"type":"string"},"date":{"type":"string"}},"required":["id","amount","description"]}},
			  {"name":"delete_expense","description":"Delete an expense by id","input_schema":{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}},
			  {"name":"create_budget","description":"Create a budget for a category and month","input_schema":{"type":"object","properties":{"categoryName":{"type":"string"},"month":{"type":"string","description":"YYYY-MM"},"amountLimit":{"type":"number"}},"required":["categoryName","amountLimit"]}},
			  {"name":"delete_budget","description":"Delete a budget by id","input_schema":{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}},
			  {"name":"create_goal","description":"Create a savings goal","input_schema":{"type":"object","properties":{"name":{"type":"string"},"targetAmount":{"type":"number"},"savedAmount":{"type":"number"},"targetDate":{"type":"string"}},"required":["name","targetAmount"]}},
			  {"name":"delete_goal","description":"Delete a savings goal by id","input_schema":{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}},
			  {"name":"create_recurring","description":"Create a recurring expense","input_schema":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"frequency":{"type":"string","enum":["MONTHLY","WEEKLY"]},"categoryName":{"type":"string"},"dayOfMonth":{"type":"number"},"dayOfWeek":{"type":"number"}},"required":["name","amount","frequency"]}},
			  {"name":"delete_recurring","description":"Delete a recurring expense by id","input_schema":{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}}
			]
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
		user.put("content", SQL_SYSTEM_PROMPT + "\n\nQuestion: " + question);

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
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 256);
		body.put("system", resolveSummaryPrompt(mode));
		ArrayNode messages = body.putArray("messages");
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", "Question: " + question + "\nData: " + dataJson);

		String raw = claudeRestClient.post()
				.uri("/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			return root.path("content").path(0).path("text").asText("").trim();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude summary response", e);
		}
	}

	@Override
	public String generateInsightSummary(String contextJson, String insightType, String mode) {
		String systemPrompt = "recommendations".equals(insightType) ? RECOMMENDATIONS_PROMPT : INSIGHT_SUMMARY_PROMPT;
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 512);
		body.put("system", systemPrompt);
		ArrayNode messages = body.putArray("messages");
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", contextJson);

		String raw = claudeRestClient.post()
				.uri("/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			return root.path("content").path(0).path("text").asText("").trim();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude insight response", e);
		}
	}

	@Override
	public ChatToolCall classifyIntent(String message) {
		try {
			JsonNode tools = objectMapper.readTree(TOOL_DEFINITIONS);
			ObjectNode body = objectMapper.createObjectNode();
			body.put("model", claudeProperties.getModel());
			body.put("max_tokens", 1024);
			body.set("tools", tools);
			ArrayNode messages = body.putArray("messages");
			ObjectNode user = messages.addObject();
			user.put("role", "user");
			user.put("content", message);

			String raw = claudeRestClient.post()
					.uri("/messages")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body.toString())
					.retrieve()
					.body(String.class);

			JsonNode root = objectMapper.readTree(raw);
			String stopReason = root.path("stop_reason").asText("");
			if ("tool_use".equals(stopReason)) {
				for (JsonNode block : root.path("content")) {
					if ("tool_use".equals(block.path("type").asText())) {
						String paramsJson = objectMapper.writeValueAsString(block.path("input"));
						return new ChatToolCall(block.path("name").asText(), paramsJson);
					}
				}
			}
			String text = root.path("content").path(0).path("text").asText("");
			return new ChatToolCall("text", text);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to classify intent via Claude", e);
		}
	}

	static String extractSql(String content) {
		if (content == null) return "";
		String t = content.trim();
		Matcher m = SQL_FENCE.matcher(t);
		return m.find() ? m.group(1).trim() : t;
	}
}
