package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.controller.ConversationController;
import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

	@GetMapping
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@Operation(operationId = "v2ListConversations")
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
