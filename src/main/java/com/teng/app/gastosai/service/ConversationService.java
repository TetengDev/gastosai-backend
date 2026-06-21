package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.ChatMessage;
import com.teng.app.gastosai.entity.ChatMessageRole;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.ChatMessageRepository;
import com.teng.app.gastosai.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Persists chat conversations + their messages so the assistant has a browsable history per user. */
@Service
@RequiredArgsConstructor
public class ConversationService {

	private static final int MAX_TITLE_LENGTH = 60;

	private final ConversationRepository conversationRepository;
	private final ChatMessageRepository chatMessageRepository;

	/** Returns the user's conversation by id, or creates a fresh one when id is null or not owned by the user. */
	@Transactional
	public Conversation getOrCreate(User user, Long conversationId) {
		if (conversationId != null) {
			Conversation existing = conversationRepository.findByIdAndUser(conversationId, user).orElse(null);
			if (existing != null) {
				return existing;
			}
		}
		LocalDateTime now = LocalDateTime.now();
		return conversationRepository.save(Conversation.builder()
				.user(user)
				.createdAt(now)
				.updatedAt(now)
				.build());
	}

	/** Appends the user message + the assistant response to the conversation and bumps its updated_at / title. */
	@Transactional
	public void recordTurn(Conversation conversation, String userContent, ChatResponse response) {
		chatMessageRepository.save(ChatMessage.builder()
				.conversation(conversation)
				.role(ChatMessageRole.USER)
				.content(userContent)
				.build());
		chatMessageRepository.save(ChatMessage.builder()
				.conversation(conversation)
				.role(ChatMessageRole.ASSISTANT)
				.content(response.message())
				.responseType(response.type())
				.build());

		if (conversation.getTitle() == null && userContent != null && !userContent.isBlank()) {
			conversation.setTitle(truncateTitle(userContent));
		}
		conversation.setUpdatedAt(LocalDateTime.now());
		conversationRepository.save(conversation);
	}

	/** Remembers the entity a turn just acted on, so a later "delete it"/"make it 500" can resolve it. */
	@Transactional
	public void recordEntity(Conversation conversation, String entityType, Long entityId) {
		conversation.setLastEntityType(entityType);
		conversation.setLastEntityId(entityId);
		conversationRepository.save(conversation);
	}

	/** A compact transcript of the last {@code limit} messages (oldest→newest) for LLM follow-up context. */
	@Transactional(readOnly = true)
	public String recentTranscript(Conversation conversation, int limit) {
		List<ChatMessage> all = chatMessageRepository.findByConversationOrderByCreatedAtAsc(conversation);
		int from = Math.max(0, all.size() - limit);
		return all.subList(from, all.size()).stream()
				.map(m -> (m.getRole() == ChatMessageRole.USER ? "User: " : "Assistant: ") + trimLine(m.getContent()))
				.collect(java.util.stream.Collectors.joining("\n"));
	}

	private static String trimLine(String s) {
		if (s == null) return "";
		String one = s.replaceAll("\\s+", " ").strip();
		return one.length() <= 200 ? one : one.substring(0, 200) + "…";
	}

	@Transactional(readOnly = true)
	public List<ConversationSummaryDto> list(User user) {
		return conversationRepository.findByUserOrderByUpdatedAtDesc(user).stream()
				.map(c -> new ConversationSummaryDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ChatMessageDto> messages(Long conversationId, User user) {
		Conversation conversation = conversationRepository.findByIdAndUser(conversationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));
		return chatMessageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
				.map(m -> new ChatMessageDto(m.getId(), m.getRole().name(), m.getContent(),
						m.getToolName(), m.getResponseType(), m.getCreatedAt()))
				.toList();
	}

	@Transactional
	public void delete(Long conversationId, User user) {
		Conversation conversation = conversationRepository.findByIdAndUser(conversationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));
		chatMessageRepository.deleteByConversation(conversation);
		conversationRepository.delete(conversation);
	}

	private static String truncateTitle(String text) {
		String trimmed = text.strip();
		return trimmed.length() <= MAX_TITLE_LENGTH ? trimmed : trimmed.substring(0, MAX_TITLE_LENGTH).strip() + "…";
	}
}
