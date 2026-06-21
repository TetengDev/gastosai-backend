package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.ConversationService;
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

@RestController
@RequestMapping("/chat/conversations")
@RequiredArgsConstructor
public class ConversationController {

	private final ConversationService conversationService;

	@GetMapping
	@RequiresFeature(FeatureKey.NL_CHATBOT)
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
