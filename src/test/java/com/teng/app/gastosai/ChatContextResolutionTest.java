package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ConversationRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.ChatActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Phase 2b-2: a created expense is recorded as the conversation's last entity (deterministic half of the hybrid). */
@SpringBootTest
class ChatContextResolutionTest {

	@Autowired ChatActionService chatActionService;
	@Autowired UserRepository userRepository;
	@Autowired ConversationRepository conversationRepository;

	@MockitoBean SqlGenerator sqlGenerator;

	private User user;

	@BeforeEach
	void setUp() {
		conversationRepository.deleteAll();
		userRepository.deleteAll();
		user = userRepository.save(User.builder().name("Ctx").email("ctx@test.com").password("x").build());
	}

	@Test
	void createExpenseTurn_recordsLastEntityOnConversation() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(new ChatToolCall("create_expense", "{\"amount\": 12.50, \"description\": \"coffee\"}"));

		ChatResponse res = chatActionService.dispatch("add 12.50 coffee", "execute", user, null);

		assertThat(res.conversationId()).isNotNull();
		Conversation conv = conversationRepository.findById(res.conversationId()).orElseThrow();
		assertThat(conv.getLastEntityType()).isEqualTo("expense");
		assertThat(conv.getLastEntityId()).isNotNull();
	}

	@Test
	void secondTurn_reusesSameConversation_andKeepsHistory() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(new ChatToolCall("text", "Sure!"));

		ChatResponse first = chatActionService.dispatch("hi", "plain", user, null);
		Long convId = first.conversationId();
		ChatResponse second = chatActionService.dispatch("again", "plain", user, convId);

		assertThat(second.conversationId()).isEqualTo(convId);
		assertThat(conversationRepository.count()).isEqualTo(1);
	}
}
