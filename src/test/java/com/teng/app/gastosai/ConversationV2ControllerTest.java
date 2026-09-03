package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the published response shape of {@code GET /api/v2/chat/conversations}.
 *
 * <p>TEN-169 typed the v1 path but owned only {@code ConversationController}, so the v2 sibling
 * kept a {@code * / *} response and an undocumented operation while sharing the improved
 * {@code ConversationSummaryDto} schema through its {@code $ref}. This asserts the two halves now
 * match, since nothing else in the build compares one path's published operation against the
 * other's. The schema itself is covered once, by {@code ConversationControllerTest}.
 */
@SpringBootTest
class ConversationV2ControllerTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Test
	void v2ListConversationsPublishesAJsonArrayOfConversationSummaries() throws Exception {
		JsonNode operation = spec().path("paths").path("/api/v2/chat/conversations").path("get");
		assertFalse(operation.isMissingNode(),
				"GET /api/v2/chat/conversations is absent from the published spec.");

		assertEquals("v2ListConversations", operation.path("operationId").asText(),
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
	void v2ListConversationsIsDocumentedToTheSameStandardAsV1() throws Exception {
		JsonNode spec = spec();
		JsonNode v2 = spec.path("paths").path("/api/v2/chat/conversations").path("get");
		JsonNode v1 = spec.path("paths").path("/chat/conversations").path("get");

		// v2 is the path clients are told to migrate to, so it may not publish less than v1.
		assertFalse(v2.path("summary").asText().isBlank(),
				"The v2 list operation publishes no summary while v1 does.");
		assertFalse(v2.path("description").asText().isBlank(),
				"The v2 list operation publishes no description while v1 does.");
		assertEquals(v1.path("summary").asText(), v2.path("summary").asText(),
				"The two paths list the same thing and should say so identically.");

		String responseDescription = v2.path("responses").path("200").path("description").asText();
		assertFalse(responseDescription.isBlank() || "OK".equals(responseDescription),
				"The v2 200 response is still springdoc's default 'OK': " + responseDescription);
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
