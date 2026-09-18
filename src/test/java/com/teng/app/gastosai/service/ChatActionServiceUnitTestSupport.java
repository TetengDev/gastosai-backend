package com.teng.app.gastosai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Shared wiring for the ChatActionService unit tests (TEN-401).
 *
 * <p>Every collaborator is a mock, so these tests run without a Spring context and without a
 * database — the class under test is a dispatcher over services it does not own, and its branching
 * is what is being asserted, not the services' behaviour.
 *
 * <p>{@code transactionManager} is a plain mock on purpose. {@code TransactionTemplate} calls
 * {@code getTransaction}, then either {@code commit} or {@code rollback}, and those three calls are
 * exactly what a test needs to see to show that a handler ran inside one transaction and that a
 * failure rolled it back.
 */
abstract class ChatActionServiceUnitTestSupport {

	@Mock SqlGenerator sqlGenerator;
	@Mock ExpenseService expenseService;
	@Mock BudgetService budgetService;
	@Mock SavingsGoalService savingsGoalService;
	@Mock RecurringExpenseService recurringExpenseService;
	@Mock CategoryService categoryService;
	@Mock UserProfileService userProfileService;
	@Mock EntitlementService entitlementService;
	@Mock AlertService alertService;
	@Mock ExpenseRepository expenseRepository;
	@Mock RecurringExpenseRepository recurringExpenseRepository;
	@Mock BudgetRepository budgetRepository;
	@Mock SavingsGoalRepository savingsGoalRepository;
	@Mock PlatformTransactionManager transactionManager;
	@Spy ObjectMapper objectMapper;
	@Mock AiQuotaService aiQuotaService;
	@Mock AiUsageService aiUsageService;
	@Mock AiRedactionService aiRedactionService;
	@Mock AiManagedProperties aiManagedProperties;
	@Mock AiProviderProperties aiProviderProperties;
	@Mock OpenAiProperties openAiProperties;
	@Mock ClaudeProperties claudeProperties;
	@Mock ConversationService conversationService;
	@Mock ChatAuditService chatAuditService;

	@InjectMocks ChatActionService chatActionService;

	@BeforeEach
	void setUpChatActionServiceMocks() {
		lenient().when(aiRedactionService.redact(anyString())).thenAnswer(i -> i.getArgument(0));
		lenient().when(aiManagedProperties.getMaxPromptChars()).thenReturn(8000);
		lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
		lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
	}

	User user() {
		return User.builder()
				.id(1L)
				.role(Role.USER)
				.email("u@test.com")
				.name("Test User")
				.password("x")
				.build();
	}

	User admin() {
		return User.builder()
				.id(2L)
				.role(Role.ADMIN)
				.email("admin@test.com")
				.name("Admin")
				.password("x")
				.build();
	}

	/** Runs one tool call through the natural-language path with the classifier stubbed to return it. */
	ChatResponse run(String toolName, String paramsJson, String mode, User user) {
		when(sqlGenerator.classifyIntent(any()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall(toolName, paramsJson)));
		return chatActionService.dispatch("(message)", mode, user);
	}

	ChatResponse run(String toolName, String paramsJson) {
		return run(toolName, paramsJson, "execute", user());
	}
}
