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
			- expenses(id bigint, amount numeric, category_id bigint, date timestamp, description text, user_id bigint)
			- categories(id bigint, name varchar)

			When you need the category name, join:
			LEFT JOIN categories c ON c.id = e.category_id
			and select from `c.name`.
			Rules:
			- Output only the SQL, no markdown unless you wrap it in a single ```sql code block.
			- SELECT only; no semicolons at the end.
			- The FROM clause must include the `expenses` table (aliases like e are fine). Joins to `categories` are allowed.
			- ALWAYS use PostgreSQL date functions. NEVER use MySQL functions like MONTH(), YEAR(), DATE_FORMAT(), or WEEKDAY().
			- For current month: WHERE date >= date_trunc('month', NOW()) AND date < date_trunc('month', NOW()) + INTERVAL '1 month'
			- For specific month extraction: EXTRACT(MONTH FROM date) or date_part('month', date)
			- For specific year: EXTRACT(YEAR FROM date)
			- For today: CURRENT_DATE, for now: NOW()
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
			  {"type":"function","function":{"name":"create_expense","description":"Create a new expense","parameters":{"type":"object","properties":{"amount":{"type":"number"},"category":{"type":"string"},"description":{"type":"string"},"date":{"type":"string","description":"ISO date"}},"required":["amount","description"]}}},
			  {"type":"function","function":{"name":"update_expense","description":"Update an existing expense by id","parameters":{"type":"object","properties":{"id":{"type":"number"},"amount":{"type":"number"},"category":{"type":"string"},"description":{"type":"string"},"date":{"type":"string"}},"required":["id","amount","description"]}}},
			  {"type":"function","function":{"name":"delete_expense","description":"Delete an expense. Use id if known, description keywords to find by name, or set latest=true to delete the most recent one.","parameters":{"type":"object","properties":{"id":{"type":"number","description":"Expense ID if known"},"description":{"type":"string","description":"Keywords to find the expense by description"},"latest":{"type":"boolean","description":"true to delete the most recently added expense"}},"required":[]}}},
			  {"type":"function","function":{"name":"create_budget","description":"Create a budget for a category and month","parameters":{"type":"object","properties":{"categoryName":{"type":"string"},"month":{"type":"string","description":"YYYY-MM"},"amountLimit":{"type":"number"}},"required":["categoryName","amountLimit"]}}},
			  {"type":"function","function":{"name":"delete_budget","description":"Delete a budget. Use id if known, or categoryName + optional month (YYYY-MM, defaults to current month).","parameters":{"type":"object","properties":{"id":{"type":"number","description":"Budget ID if known"},"categoryName":{"type":"string","description":"Category name to find the budget"},"month":{"type":"string","description":"YYYY-MM, defaults to current month"}},"required":[]}}},
			  {"type":"function","function":{"name":"create_goal","description":"Create a savings goal","parameters":{"type":"object","properties":{"name":{"type":"string"},"targetAmount":{"type":"number"},"savedAmount":{"type":"number"},"targetDate":{"type":"string"}},"required":["name","targetAmount"]}}},
			  {"type":"function","function":{"name":"delete_goal","description":"Delete a savings goal. Use id if known, or name to find it.","parameters":{"type":"object","properties":{"id":{"type":"number","description":"Goal ID if known"},"name":{"type":"string","description":"Goal name to search for"}},"required":[]}}},
			  {"type":"function","function":{"name":"create_recurring","description":"Create a recurring expense","parameters":{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"},"frequency":{"type":"string","enum":["MONTHLY","WEEKLY"]},"categoryName":{"type":"string"},"dayOfMonth":{"type":"number"},"dayOfWeek":{"type":"number"}},"required":["name","amount","frequency"]}}},
			  {"type":"function","function":{"name":"delete_recurring","description":"Delete a recurring expense. Use id if known, name to find by keyword, or set latest=true to delete the most recently added one.","parameters":{"type":"object","properties":{"id":{"type":"number","description":"Recurring expense ID if known"},"name":{"type":"string","description":"Keywords to find recurring expense by name"},"latest":{"type":"boolean","description":"true to delete the most recently added recurring expense"}},"required":[]}}}
			]
			""";

	private static final String ANALYTICS_TOOL = """
			[
			  {"type":"function","function":{"name":"analytics_query","description":"Build a structured analytics query over the user's own expenses. Choose the metric, time window, optional category filter, sort order and result limit that best answer the question.","parameters":{"type":"object","properties":{"metric":{"type":"string","enum":["TOTAL","AVERAGE","COUNT","SUM_BY_CATEGORY","SUM_BY_DAY","SUM_BY_MONTH"],"description":"TOTAL/AVERAGE/COUNT are single numbers; SUM_BY_* return a breakdown."},"dateRange":{"type":"string","enum":["CURRENT_MONTH","LAST_MONTH","LAST_3_MONTHS","YEAR_TO_DATE","ALL"]},"category":{"type":"string","description":"Optional exact category name to filter by"},"sort":{"type":"string","enum":["ASC","DESC"]},"limit":{"type":"integer","description":"Max rows for breakdowns (1-100)"}},"required":["metric","dateRange"]}}}
			]
			""";

	private static final String ANALYTICS_TOOL_NAME = "analytics_query";

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

	@Override
	public String classifyQueryIntentJson(String question) {
		if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
			return null;
		}
		try {
			JsonNode tools = objectMapper.readTree(ANALYTICS_TOOL);
			ObjectNode body = objectMapper.createObjectNode();
			body.put("model", openAiProperties.getModel());
			body.set("tools", tools);
			ObjectNode toolChoice = body.putObject("tool_choice");
			toolChoice.put("type", "function");
			toolChoice.putObject("function").put("name", ANALYTICS_TOOL_NAME);
			ArrayNode messages = body.putArray("messages");
			ObjectNode user = messages.addObject();
			user.put("role", "user");
			user.put("content", question);

			String raw = openAiRestClient.post()
					.uri("/v1/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body.toString())
					.retrieve()
					.body(String.class);

			JsonNode root = objectMapper.readTree(raw);
			JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
			if (toolCalls.isArray() && !toolCalls.isEmpty()) {
				JsonNode fn = toolCalls.get(0).path("function");
				if (ANALYTICS_TOOL_NAME.equals(fn.path("name").asText())) {
					return fn.path("arguments").asText();
				}
			}
			return null;
		}
		catch (Exception e) {
			return null;
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

	@Override
	public String generateInsightSummary(String contextJson, String insightType, String mode) {
		String systemPrompt = "recommendations".equals(insightType) ? RECOMMENDATIONS_PROMPT : INSIGHT_SUMMARY_PROMPT;
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		body.put("max_completion_tokens", 512);
		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", systemPrompt);
		ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", contextJson);

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
			throw new IllegalStateException("Failed to parse OpenAI insight response", e);
		}
	}

	@Override
	public ChatToolCall classifyIntent(String message) {
		try {
			JsonNode tools = objectMapper.readTree(TOOL_DEFINITIONS);
			ObjectNode body = objectMapper.createObjectNode();
			body.put("model", openAiProperties.getModel());
			body.set("tools", tools);
			body.put("tool_choice", "auto");
			ArrayNode messages = body.putArray("messages");
			ObjectNode user = messages.addObject();
			user.put("role", "user");
			user.put("content", message);

			String raw = openAiRestClient.post()
					.uri("/v1/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body.toString())
					.retrieve()
					.body(String.class);

			JsonNode root = objectMapper.readTree(raw);
			JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
			if (toolCalls.isArray() && !toolCalls.isEmpty()) {
				JsonNode fn = toolCalls.get(0).path("function");
				return new ChatToolCall(fn.path("name").asText(), fn.path("arguments").asText());
			}
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			return new ChatToolCall("text", content);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to classify intent via OpenAI", e);
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
