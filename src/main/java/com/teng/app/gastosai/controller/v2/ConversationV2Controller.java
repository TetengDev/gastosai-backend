package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.controller.ConversationController;
import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
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

/** {@link ConversationController} on the v2 path; a chat transcript carries no money field. */
@RestController
@RequestMapping("/api/v2/chat/conversations")
@RequiredArgsConstructor
public class ConversationV2Controller {

	private final ConversationController delegate;

	/**
	 * The v2 sibling of {@link ConversationController#list}, published to the same standard.
	 *
	 * <p>TEN-169 typed the v1 operation but owned only {@code ConversationController}, so the shared
	 * {@code ConversationSummaryDto} schema improved here through the {@code $ref} while the
	 * operation did not: v2 still published its 200 under {@code * / *} with no summary or
	 * description. A client generating from v2 — the path clients are told to migrate to — got the
	 * worse of the two contracts. The body is unchanged; only its published description is new, so
	 * this is additive: a minor contract bump.
	 */
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@Operation(
			operationId = "v2ListConversations",
			summary = "List the caller's chat conversations",
			description = "Conversation summaries for the authenticated user, most recently updated "
					+ "first. Carries no messages; fetch a transcript with "
					+ "GET /api/v2/chat/conversations/{id}.")
	@ApiResponse(
			responseCode = "200",
			description = "The caller's conversations, newest activity first. Empty when they have never chatted.",
			content = @Content(
					mediaType = MediaType.APPLICATION_JSON_VALUE,
					array = @ArraySchema(schema = @Schema(implementation = ConversationSummaryDto.class))))
	public List<ConversationSummaryDto> list(@AuthenticationPrincipal User user) {
		return delegate.list(user);
	}

	@GetMapping("/{id}")
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@Operation(operationId = "v2ConversationMessages")
	public List<ChatMessageDto> messages(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return delegate.messages(id, user);
	}

	@DeleteMapping("/{id}")
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteConversation")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}
}
