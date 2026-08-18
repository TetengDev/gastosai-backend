package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the published response shape of {@code GET /chat/conversations}.
 *
 * <p>Behaviour — ownership, 404s, the delete — is covered by {@code ConversationApiIntegrationTest}.
 * What this asserts is the contract a history surface is generated from, which the behavioural test
 * cannot see: the JSON media type, a stable operation id, and the four properties a list row needs.
 * The spec is read from {@code /v3/api-docs} rather than the committed file so a failure points at
 * the annotations that produced it, not at a stale artifact.
 */
@SpringBootTest
class ConversationControllerTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Test
	void listConversationsPublishesAJsonArrayOfConversationSummaries() throws Exception {
		JsonNode operation = spec().path("paths").path("/chat/conversations").path("get");
		assertFalse(operation.isMissingNode(), "GET /chat/conversations is absent from the published spec.");

		// A positional id like `list_5` renames itself when an unrelated `list` handler appears
		// elsewhere, silently renaming the generated client's method.
		assertEquals("listConversations", operation.path("operationId").asText(),
				"The operation id is what a generated client names this call; it must be stable and explicit.");

		JsonNode content = operation.path("responses").path("200").path("content");
		assertTrue(content.has("application/json"),
				"The 200 response must declare application/json, not a wildcard media type: " + content);

		JsonNode schema = content.path("application/json").path("schema");
		assertEquals("array", schema.path("type").asText(), "A history list is an array: " + schema);
		assertEquals("#/components/schemas/ConversationSummaryDto", schema.path("items").path("$ref").asText(),
				"The array must reference the published summary schema rather than inline an anonymous one.");
	}

	@Test
	void conversationSummarySchemaCarriesWhatAHistoryListNeeds() throws Exception {
		JsonNode schema = spec().path("components").path("schemas").path("ConversationSummaryDto");
		assertFalse(schema.isMissingNode(), "ConversationSummaryDto is absent from the published components.");

		JsonNode properties = schema.path("properties");
		for (String property : List.of("id", "title", "createdAt", "updatedAt")) {
			assertTrue(properties.has(property),
					"A history row needs " + property + "; published properties were " + properties.fieldNames());
			assertFalse(properties.path(property).path("description").asText().isBlank(),
					property + " is published without a description, so a client has to guess its meaning.");
		}

		assertEquals("int64", properties.path("id").path("format").asText());
		assertEquals("date-time", properties.path("createdAt").path("format").asText());
		assertEquals("date-time", properties.path("updatedAt").path("format").asText());

		// The distinction that matters to a rendering client: a row always has an id and timestamps,
		// and may have no title until its first turn is recorded.
		List<String> required = new ArrayList<>();
		schema.path("required").forEach(node -> required.add(node.asText()));
		assertTrue(required.containsAll(List.of("id", "createdAt", "updatedAt")),
				"id and the timestamps are always present and must be published as required: " + required);
		assertFalse(required.contains("title"),
				"title is null until the first turn, so publishing it as required would lie to the client.");
	}

	private JsonNode spec() throws Exception {
		String body = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build()
				.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return new ObjectMapper().readTree(body);
	}
}
