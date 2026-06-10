package com.teng.app.gastosai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class OpenAiExpenseParser implements ExpenseParser {


    private final RestClient openAiRestClient;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ParsedExpenseResult parse(String text) {
        if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        String systemPrompt = ExpenseParser.buildSystemPrompt(LocalDate.now(ZoneId.of("Asia/Manila")));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", openAiProperties.getModel());
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", text);

        String raw = openAiRestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return parseJson(objectMapper.readTree(content));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI expense response", e);
        }
    }

    static ParsedExpenseResult parseJson(JsonNode node) {
        BigDecimal amount = new BigDecimal(node.path("amount").asText("0"));
        String category = node.path("category").asText("Uncategorized");
        JsonNode dateNode = node.path("date");
        LocalDateTime date = (!dateNode.isNull() && !dateNode.asText("").isBlank())
                ? LocalDateTime.parse(dateNode.asText())
                : LocalDateTime.now(ZoneId.of("Asia/Manila"));
        String description = node.path("description").asText("");
        String confidence = node.path("confidence").asText("LOW");

        boolean saveable = "HIGH".equals(confidence) && amount.compareTo(BigDecimal.ZERO) > 0;
        String hint = saveable ? null : "Amount or description unclear - please provide more details.";

        return new ParsedExpenseResult(amount, category, date, description, confidence, saveable, hint);
    }
}
