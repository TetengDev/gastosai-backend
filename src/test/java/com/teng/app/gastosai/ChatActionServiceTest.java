package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AiRedactionService;
import com.teng.app.gastosai.service.AiUsageService;
import com.teng.app.gastosai.service.AlertService;
import com.teng.app.gastosai.service.BudgetService;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.ChatActionService;
import com.teng.app.gastosai.service.EntitlementService;
import com.teng.app.gastosai.service.ExpenseService;
import com.teng.app.gastosai.service.RecurringExpenseService;
import com.teng.app.gastosai.service.SavingsGoalService;
import com.teng.app.gastosai.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatActionServiceTest {

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
    // Injected so the constructor dependency is satisfied here too: handleUpdateExpense's silent
    // category path builds a TransactionTemplate from it, and a null field would NPE the first
    // unit test written for that path rather than failing where the mock is missing.
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Spy ObjectMapper objectMapper;
    @Mock AiQuotaService aiQuotaService;
    @Mock AiUsageService aiUsageService;
    @Mock AiRedactionService aiRedactionService;
    @Mock AiManagedProperties aiManagedProperties;
    @Mock AiProviderProperties aiProviderProperties;
    @Mock OpenAiProperties openAiProperties;
    @Mock ClaudeProperties claudeProperties;
    @Mock com.teng.app.gastosai.service.ConversationService conversationService;
    @Mock com.teng.app.gastosai.service.ChatAuditService chatAuditService;

    @InjectMocks ChatActionService chatActionService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(aiRedactionService.redact(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.lenient().when(aiManagedProperties.getMaxPromptChars()).thenReturn(8000);
        org.mockito.Mockito.lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
        org.mockito.Mockito.lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
    }

    private User user() {
        return User.builder()
                .id(1L)
                .role(Role.USER)
                .email("u@test.com")
                .name("Test")
                .password("x")
                .build();
    }

    @Test
    void dispatch_createExpense_withoutExecuteMode_returnsPreview() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(new ChatToolCall("create_expense", paramsJson)));

        ChatResponse response = chatActionService.dispatch("Add lunch ₱500", null, user());

        assertThat(response.type()).isEqualTo("preview");
        assertThat(response.message()).contains("500");
        assertThat(response.message()).contains("Lunch");
    }

    @Test
    void dispatch_createExpense_withExecuteMode_routesToExpenseServiceAndReturnsActionResponse() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(new ChatToolCall("create_expense", paramsJson)));

        ExpenseResponse mockResult = new ExpenseResponse(
                1L, new BigDecimal("500.00"), "Food",
                LocalDateTime.now(), "Lunch", "PERSONAL", false, "PHP",
                BigDecimal.ONE, new BigDecimal("500.00"), ExpenseSource.QUICK_ADD);
        when(expenseService.create(any(), any(), any())).thenReturn(mockResult);

        ChatResponse response = chatActionService.dispatch("Add lunch ₱500", "execute", user());

        assertThat(response.type()).isEqualTo("action");
        assertThat(response.message()).contains("Expense created");
        assertThat(response.message()).contains("500");
        assertThat(response.message()).contains("Lunch");
        assertThat(response.result()).isEqualTo(mockResult);
    }

    @Test
    void dispatch_textFallback_returnsTextResponse() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(new ChatToolCall("text", "Hello there!")));

        ChatResponse response = chatActionService.dispatch("Hi", null, user());

        assertThat(response.type()).isEqualTo("text");
        assertThat(response.message()).isEqualTo("Hello there!");
        assertThat(response.result()).isNull();
    }

    @Test
    void dispatch_resourceNotFound_returnsHelpfulTextResponse() {
        String paramsJson = """
                {"id":999}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expense", paramsJson)));
        doThrow(new ResourceNotFoundException("Expense not found: 999")).when(expenseService).delete(eq(999L), any());

        ChatResponse response = chatActionService.dispatch("Delete expense 999", null, user());

        assertThat(response.type()).isEqualTo("text");
        assertThat(response.message()).isEqualTo("I couldn't find that item.");
        assertThat(response.result()).isNull();
    }

    @Test
    void dispatch_unexpectedException_returnsSomethingWentWrongResponse() {
        when(sqlGenerator.classifyIntent(any())).thenThrow(new RuntimeException("network error"));

        ChatResponse response = chatActionService.dispatch("Do something", null, user());

        assertThat(response.type()).isEqualTo("text");
        assertThat(response.message()).contains("Something went wrong");
        assertThat(response.message()).doesNotContain("network error");
    }

    // --- TEN-166: the structured confirm path ---

    /**
     * The acceptance criterion end to end: the preview's own payload, handed back unchanged,
     * executes the proposed action — and does it without the classifier seeing any English.
     */
    @Test
    @SuppressWarnings("unchecked")
    void confirm_replayingThePreviewPayload_executesTheProposedAction() {
        String paramsJson = """
                {"categoryName":"Food","month":"2026-08","amountLimit":5000}
                """;
        when(sqlGenerator.classifyIntent(any()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("create_budget", paramsJson)));

        ChatResponse preview = chatActionService.dispatch("Budget 5000 for food", null, user());
        assertThat(preview.type()).isEqualTo("preview");

        Map<String, Object> previewData = (Map<String, Object>) preview.result();
        org.mockito.Mockito.reset(sqlGenerator);
        when(categoryService.getOrCreateByName(eq("Food"), any()))
                .thenReturn(Category.builder().id(7L).name("Food").build());

        ChatResponse confirmed = chatActionService.confirm(
                (String) previewData.get("toolName"),
                (Map<String, Object>) previewData.get("params"),
                null, user(), null);

        assertThat(confirmed.type()).isEqualTo("action");
        assertThat(confirmed.message()).contains("Budget created for Food");

        ArgumentCaptor<BudgetRequest> req = ArgumentCaptor.forClass(BudgetRequest.class);
        verify(budgetService).create(req.capture(), any());
        assertThat(req.getValue().categoryId()).isEqualTo(7L);
        assertThat(req.getValue().month()).isEqualTo("2026-08");
        assertThat(req.getValue().amountLimit()).isEqualByComparingTo("5000");

        // No English went out on the confirming turn, so no model ran.
        verifyNoInteractions(sqlGenerator);
    }

    @Test
    void confirm_doesNotCallTheModelOrMeterUsage() {
        chatActionService.confirm("list_categories", Map.of(), null, user(), null);

        verifyNoInteractions(sqlGenerator, aiQuotaService, aiUsageService);
        verify(chatAuditService).record(eq(1L), any(), eq("list_categories"),
                eq(com.teng.app.gastosai.entity.AiUsageStatus.SUCCESS), eq("confirm"));
    }

    @Test
    void confirm_unknownToolName_isRejectedRatherThanAnsweredAsText() {
        User u = user();
        Map<String, Object> params = Map.of();

        assertThatThrownBy(() -> chatActionService.confirm("drop_database", params, null, u, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(sqlGenerator, expenseService, budgetService);
    }

    @Test
    void confirm_createExpense_stillRunsTheDuplicateCheckUnlessForced() {
        when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any()))
                .thenReturn(List.of(existingLunch()));

        ChatResponse response = chatActionService.confirm("create_expense",
                Map.of("amount", 500, "description", "Lunch", "category", "Food"),
                null, user(), null);

        assertThat(response.type()).isEqualTo("disambiguate");
        assertThat(response.message()).contains("duplicate");
        verifyNoInteractions(expenseService);
    }

    @Test
    void confirm_forceMode_bypassesTheDuplicateCheck() {
        ExpenseResponse created = new ExpenseResponse(
                1L, new BigDecimal("500.00"), "Food",
                LocalDateTime.now(), "Lunch", "PERSONAL", false, "PHP",
                BigDecimal.ONE, new BigDecimal("500.00"), ExpenseSource.QUICK_ADD);
        when(expenseService.create(any(), any(), any())).thenReturn(created);

        ChatResponse response = chatActionService.confirm("create_expense",
                Map.of("amount", 500, "description", "Lunch", "category", "Food"),
                "force", user(), null);

        assertThat(response.type()).isEqualTo("action");
        assertThat(response.result()).isEqualTo(created);
        verifyNoInteractions(expenseRepository);
    }

    @Test
    void confirm_failingAction_isReportedAsTextAndAudited() {
        doThrow(new ResourceNotFoundException("Expense not found: 999"))
                .when(expenseService).delete(eq(999L), any());

        ChatResponse response = chatActionService.confirm("delete_expense",
                Map.of("id", 999), null, user(), null);

        assertThat(response.type()).isEqualTo("text");
        assertThat(response.message()).isEqualTo("I couldn't find that item.");
        verify(chatAuditService).record(eq(1L), any(), eq("delete_expense"),
                eq(com.teng.app.gastosai.entity.AiUsageStatus.FAILED), eq("ResourceNotFoundException"));
    }

    private Expense existingLunch() {
        return Expense.builder()
                .id(42L)
                .amount(new BigDecimal("500.00"))
                .description("Lunch")
                .date(LocalDateTime.now().minusDays(1))
                .build();
    }
}
