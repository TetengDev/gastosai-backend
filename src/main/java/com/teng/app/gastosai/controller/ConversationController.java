package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/chat/conversations")
@RequiredArgsConstructor
public class ConversationController {

	private final ConversationService conversationService;

	/**
	 * The chat history list, most recently updated first.
	 *
	 * <p>Declared with an explicit media type, operation id and response schema because this is the
	 * endpoint a history surface is generated from. Left to springdoc's defaults it published a
	 * {@code * / *} response and the operation id {@code list_5} — a positional name, renumbered by
	 * any unrelated {@code list} handler added elsewhere, which makes a generated client's method
	 * name change without this endpoint changing at all.
	 *
	 * <p>The body is unchanged: the same {@code List<ConversationSummaryDto>} the service has always
	 * returned, ordered by {@code updatedAt} descending in
	 * {@code ConversationRepository.findByUserOrderByUpdatedAtDesc}. Only the published description
	 * of it is new, so this is additive — a minor contract bump.
	 */
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@Operation(
			operationId = "listConversations",
			summary = "List the caller's chat conversations",
			description = "Conversation summaries for the authenticated user, most recently updated "
					+ "first. Carries no messages; fetch a transcript with GET /chat/conversations/{id}.")
	@ApiResponse(
			responseCode = "200",
			description = "The caller's conversations, newest activity first. Empty when they have never chatted.",
			content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = ConversationSummaryDto.class))))
	public List<ConversationSummaryDto> list(@AuthenticationPrincipal User user) {
		return conversationService.list(user);
	}

	@GetMapping("/{id}")
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	public List<ChatMessageDto> messages(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return conversationService.messages(id, user);
	}

	@DeleteMapping("/{id}")
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		conversationService.delete(id, user);
	}
}
