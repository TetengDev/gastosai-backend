package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class VisionService {

	private static final String SYSTEM_PROMPT_TEMPLATE = """
			You are GastosAI, an AI receipt parser for a Filipino expense tracker.
			Analyze the image and return ONLY a raw JSON object — no markdown fences, no explanation.
			The JSON must match this exact shape:
			{"amount":250.00,"category":"Food","date":"2026-06-11T00:00:00","description":"Jollibee lunch","confidence":"high","saveable":true,"hint":null,"rejectionMessage":null}
			Fields: amount (number or null), category (string or null), date (ISO-8601 LocalDateTime or null),
			description (string or null), confidence ("high"/"medium"/"low"), saveable (boolean), hint (string or null), rejectionMessage (string or null).
			If the image is a receipt/bill/financial document: populate amount, category, date, description; set saveable=true; hint=null; rejectionMessage=null.
			If the image is NOT expense-related: set saveable=false, hint=<brief description of what you see>, other fields null.
			Chat mode: %s — plain=clear friendly English, professional=formal business tone, genz=casual Gen Z slang with emojis.
			When saveable=false: populate rejectionMessage with a short mode-appropriate message telling the user this is not a receipt and to attach one instead.
			""";

	private final RestClient claudeRestClient;
	private final RestClient openAiRestClient;
	private final AiProviderProperties providerProps;
	private final ClaudeProperties claudeProperties;
	private final OpenAiProperties openAiProperties;
	private final ObjectMapper objectMapper;

	public ParsedExpenseResult analyze(String question, MultipartFile file, String mode) throws IOException {
		String prompt = (question != null && !question.isBlank())
				? question
				: "Extract expense information from this image.";
		String base64 = Base64.getEncoder().encodeToString(file.getBytes());
		String mediaType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
		String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, mode != null ? mode : "plain");

		return "claude".equalsIgnoreCase(providerProps.getProvider())
				? callClaude(prompt, base64, mediaType, systemPrompt)
				: callOpenAi(prompt, base64, mediaType, systemPrompt);
	}

	private ParsedExpenseResult callClaude(String prompt, String base64, String mediaType, String systemPrompt) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 1024);
		body.put("system", systemPrompt);

		ArrayNode messages = body.putArray("messages");
		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		ArrayNode content = userMsg.putArray("content");

		ObjectNode imgBlock = content.addObject();
		imgBlock.put("type", "image");
		ObjectNode source = imgBlock.putObject("source");
		source.put("type", "base64");
		source.put("media_type", mediaType);
		source.put("data", base64);

		ObjectNode textBlock = content.addObject();
		textBlock.put("type", "text");
		textBlock.put("text", prompt);

		String raw = claudeRestClient.post()
				.uri("/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			String text = root.path("content").path(0).path("text").asText("").trim();
			return parseResult(text);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude vision response", e);
		}
	}

	private ParsedExpenseResult callOpenAi(String prompt, String base64, String mediaType, String systemPrompt) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		body.put("max_completion_tokens", 1024);

		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", systemPrompt);

		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		ArrayNode content = userMsg.putArray("content");

		ObjectNode imgBlock = content.addObject();
		imgBlock.put("type", "image_url");
		imgBlock.putObject("image_url").put("url", "data:" + mediaType + ";base64," + base64);

		ObjectNode textBlock = content.addObject();
		textBlock.put("type", "text");
		textBlock.put("text", prompt);

		String raw = openAiRestClient.post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
			return parseResult(text);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse OpenAI vision response", e);
		}
	}

	private ParsedExpenseResult parseResult(String rawText) {
		String cleaned = rawText
				.replaceAll("(?s)^```json\\s*", "")
				.replaceAll("(?s)^```\\s*", "")
				.replaceAll("(?s)\\s*```$", "")
				.trim();
		try {
			return objectMapper.readValue(cleaned, ParsedExpenseResult.class);
		} catch (JsonProcessingException e) {
			return new ParsedExpenseResult(null, null, null, null, "low", false, rawText, null);
		}
	}
}
