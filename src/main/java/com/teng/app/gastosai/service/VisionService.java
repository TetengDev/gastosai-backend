package com.teng.app.gastosai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.AiQueryResponse;
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

	private static final String SYSTEM_PROMPT = """
			You are GastosAI, an AI expense assistant for a Filipino user tracking expenses in Philippine Peso (₱).
			The user has shared an image. Analyze it and:
			- If it is a receipt, bill, or financial document: extract the merchant name, total amount, date, and suggest a category (e.g. Food, Transport, Utilities).
			- If it is not expense-related: briefly describe what you see.
			- Format monetary amounts as ₱ X,XXX.XX when the currency appears to be PHP; otherwise use the visible symbol.
			- Be concise and structured. Do not mention SQL, databases, or technical implementation details.
			""";

	private final RestClient claudeRestClient;
	private final RestClient openAiRestClient;
	private final AiProviderProperties providerProps;
	private final ClaudeProperties claudeProperties;
	private final OpenAiProperties openAiProperties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AiQueryResponse analyze(String question, MultipartFile file) throws IOException {
		String prompt = (question != null && !question.isBlank())
				? question
				: "What expense information can you extract from this image?";
		String base64 = Base64.getEncoder().encodeToString(file.getBytes());
		String mediaType = file.getContentType() != null ? file.getContentType() : "image/jpeg";

		return "claude".equalsIgnoreCase(providerProps.getProvider())
				? callClaude(prompt, base64, mediaType)
				: callOpenAi(prompt, base64, mediaType);
	}

	private AiQueryResponse callClaude(String prompt, String base64, String mediaType) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 1024);
		body.put("system", SYSTEM_PROMPT);

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
			return new AiQueryResponse(root.path("content").path(0).path("text").asText("").trim());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude vision response", e);
		}
	}

	private AiQueryResponse callOpenAi(String prompt, String base64, String mediaType) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		body.put("max_completion_tokens", 1024);

		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", SYSTEM_PROMPT);

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
			return new AiQueryResponse(
					root.path("choices").path(0).path("message").path("content").asText("").trim());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse OpenAI vision response", e);
		}
	}
}
