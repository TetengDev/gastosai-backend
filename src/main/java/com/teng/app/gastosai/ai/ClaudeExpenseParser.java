package com.teng.app.gastosai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ClaudeExpenseParser implements ExpenseParser {


    private static final Pattern JSON_FENCE = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");
    private static final Pattern JSON_BARE = Pattern.compile("(?s)(\\{.*})");

    private final RestClient claudeRestClient;
    private final ClaudeProperties claudeProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ParsedExpenseResult parse(String text) {
        if (claudeProperties.getApiKey() == null || claudeProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("CLAUDE_API_KEY is not configured");
        }

        String systemPrompt = ExpenseParser.buildSystemPrompt(LocalDate.now(ZoneId.of("Asia/Manila")));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", claudeProperties.getModel());
        body.put("max_tokens", 512);
        body.put("system", systemPrompt);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", text);

        String raw = claudeRestClient.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("content").path(0).path("text").asText("").trim();
            String jsonStr = extractJson(content);
            return OpenAiExpenseParser.parseJson(objectMapper.readTree(jsonStr));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Claude expense response", e);
        }
    }

    static String extractJson(String content) {
        Matcher fenced = JSON_FENCE.matcher(content);
        if (fenced.find()) return fenced.group(1).trim();
        Matcher bare = JSON_BARE.matcher(content);
        if (bare.find()) return bare.group(1).trim();
        return content;
    }
}