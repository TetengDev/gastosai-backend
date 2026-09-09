package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.ChatAuditLogRepository;
import com.teng.app.gastosai.repository.ConversationRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.ChatActionService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 2b-2: a created expense is recorded as the conversation's last entity (deterministic half of the hybrid). */
@SpringBootTest
class ChatContextResolutionTest extends PostgresBackedTest {

	@Autowired ChatActionService chatActionService;
	@Autowired UserRepository userRepository;
	@Autowired ConversationRepository conversationRepository;
	@Autowired ChatAuditLogRepository chatAuditLogRepository;

	@MockitoBean SqlGenerator sqlGenerator;

	private User user;

	@BeforeEach
	void setUp() {
		chatAuditLogRepository.deleteAll();
		conversationRepository.deleteAll();
		userRepository.deleteAll();
		user = userRepository.save(User.builder().name("Ctx").email("ctx@test.com").password("x").build());
	}

	@Test
	void createExpenseTurn_recordsLastEntityOnConversation() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("create_expense", "{\"amount\": 12.50, \"description\": \"coffee\"}")));

		ChatResponse res = chatActionService.dispatch("add 12.50 coffee", "execute", user, null);

		assertThat(res.conversationId()).isNotNull();
		Conversation conv = conversationRepository.findById(res.conversationId()).orElseThrow();
		assertThat(conv.getLastEntityType()).isEqualTo("expense");
		assertThat(conv.getLastEntityId()).isNotNull();

		var audit = chatAuditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
		assertThat(audit).hasSize(1);
		assertThat(audit.get(0).getToolName()).isEqualTo("create_expense");
		assertThat(audit.get(0).getStatus()).isEqualTo(AiUsageStatus.SUCCESS);
	}

	/**
	 * TEN-364: a recurring-expense turn is the conversation's referent too, so the follow-up is
	 * pointed at the recurring row rather than at whatever expense happened to precede it.
	 */
	@Test
	void createRecurringTurn_thenFollowUp_resolvesAgainstTheRecurringExpense() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("create_expense", "{\"amount\": 12.50, \"description\": \"coffee\"}")));
		ChatResponse first = chatActionService.dispatch("add 12.50 coffee", "execute", user, null);
		Long convId = first.conversationId();
		Long expenseId = conversationRepository.findById(convId).orElseThrow().getLastEntityId();

		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("create_recurring",
						"{\"name\": \"Netflix\", \"amount\": 549, \"frequency\": \"MONTHLY\"}")));
		chatActionService.dispatch("add a monthly 549 Netflix bill", "execute", user, convId);

		Conversation conv = conversationRepository.findById(convId).orElseThrow();
		assertThat(conv.getLastEntityType()).isEqualTo("recurring");
		assertThat(conv.getLastEntityId()).isNotNull().isNotEqualTo(expenseId);
		Long recurringId = conv.getLastEntityId();

		// The follow-up turn is handed the recurring id, aimed at the recurring tools.
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("text", "ok")));
		chatActionService.dispatch("make it 600", "plain", user, convId);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(sqlGenerator, atLeastOnce()).classifyIntent(prompt.capture());
		String followUp = prompt.getValue();
		assertThat(followUp).contains("use recurring expense id " + recurringId)
				.contains("update_recurring")
				.doesNotContain("use expense id");
	}

	/** The expense hint is untouched by TEN-364 — same wording, same id. */
	@Test
	void createExpenseTurn_thenFollowUp_stillResolvesAgainstTheExpense() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("create_expense", "{\"amount\": 12.50, \"description\": \"coffee\"}")));
		ChatResponse first = chatActionService.dispatch("add 12.50 coffee", "execute", user, null);
		Long convId = first.conversationId();
		Long expenseId = conversationRepository.findById(convId).orElseThrow().getLastEntityId();

		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("text", "ok")));
		chatActionService.dispatch("delete it", "plain", user, convId);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(sqlGenerator, atLeastOnce()).classifyIntent(prompt.capture());
		assertThat(prompt.getValue())
				.contains("use expense id " + expenseId)
				.doesNotContain("recurring expense id");
	}

	@Test
	void secondTurn_reusesSameConversation_andKeepsHistory() {
		when(sqlGenerator.classifyIntent(anyString()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("text", "Sure!")));

		ChatResponse first = chatActionService.dispatch("hi", "plain", user, null);
		Long convId = first.conversationId();
		ChatResponse second = chatActionService.dispatch("again", "plain", user, convId);

		assertThat(second.conversationId()).isEqualTo(convId);
		assertThat(conversationRepository.count()).isEqualTo(1);
	}
}
