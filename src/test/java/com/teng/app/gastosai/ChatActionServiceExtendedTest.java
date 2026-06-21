package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AiRedactionService;
import com.teng.app.gastosai.service.AiUsageService;
import com.teng.app.gastosai.service.AlertService;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UserProfileResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.GoalStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatActionServiceExtendedTest {

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
    void setUpBase() {
        lenient().when(aiRedactionService.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(aiManagedProperties.getMaxPromptChars()).thenReturn(8000);
        lenient().when(aiProviderProperties.getProvider()).thenReturn("openai");
        lenient().when(openAiProperties.getModel()).thenReturn("gpt-4o-mini");
    }

    private User user() {
        return User.builder()
                .id(1L)
                .role(Role.USER)
                .email("u@test.com")
                .name("Test User")
                .nickname("tester")
                .password("x")
                .build();
    }

    // --- Categories ---

    @Test
    void createCategory_returnsAction() {
        String paramsJson = """
                {"name":"Groceries","icon":"shopping-cart"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_category", paramsJson));
        when(categoryService.create(any(), any())).thenReturn(new CategoryResponse(10L, "Groceries", "shopping-cart"));

        ChatResponse resp = chatActionService.dispatch("Add a Groceries category", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("Groceries");
    }

    @Test
    void createCategory_withoutExecuteMode_returnsPreview() {
        String paramsJson = """
                {"name":"Groceries"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_category", paramsJson));

        ChatResponse resp = chatActionService.dispatch("Add a Groceries category", null, user());

        assertThat(resp.type()).isEqualTo("preview");
        assertThat(resp.message()).contains("Groceries");
    }

    @Test
    void createCategory_duplicate_returnsTextWithMessage() {
        String paramsJson = """
                {"name":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_category", paramsJson));
        when(categoryService.create(any(), any())).thenThrow(new IllegalArgumentException("Category already exists: Food"));

        ChatResponse resp = chatActionService.dispatch("Add a Food category", "execute", user());

        assertThat(resp.type()).isEqualTo("text");
        assertThat(resp.message()).contains("Food");
    }

    @Test
    void renameCategory_returnsAction() {
        String paramsJson = """
                {"currentName":"Food","newName":"Meals"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("rename_category", paramsJson));
        when(categoryService.findAll(any())).thenReturn(List.of(new CategoryResponse(5L, "Food", null)));
        when(categoryService.update(eq(5L), any(), any())).thenReturn(new CategoryResponse(5L, "Meals", null));

        ChatResponse resp = chatActionService.dispatch("Rename Food to Meals", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("Meals");
    }

    @Test
    void renameCategory_uncategorized_returnsProtectedMessage() {
        String paramsJson = """
                {"currentName":"Uncategorized","newName":"Other"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("rename_category", paramsJson));

        ChatResponse resp = chatActionService.dispatch("Rename uncategorized", null, user());

        assertThat(resp.type()).isEqualTo("text");
        assertThat(resp.message()).containsIgnoringCase("cannot be renamed");
    }

    @Test
    void renameCategory_notFound_returnsText() {
        String paramsJson = """
                {"currentName":"Nonexistent","newName":"Other"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("rename_category", paramsJson));
        when(categoryService.findAll(any())).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Rename nonexistent", null, user());

        assertThat(resp.type()).isEqualTo("text");
        assertThat(resp.message()).containsIgnoringCase("Nonexistent");
    }

    @Test
    void deleteCategory_uncategorized_returnsProtectedMessage() {
        String paramsJson = """
                {"name":"Uncategorized"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("delete_category", paramsJson));

        ChatResponse resp = chatActionService.dispatch("Delete uncategorized", null, user());

        assertThat(resp.type()).isEqualTo("text");
        assertThat(resp.message()).containsIgnoringCase("cannot be deleted");
    }

    @Test
    void deleteCategory_returnsAction() {
        String paramsJson = """
                {"name":"OldCategory"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("delete_category", paramsJson));
        when(categoryService.findAll(any())).thenReturn(List.of(new CategoryResponse(7L, "OldCategory", null)));

        ChatResponse resp = chatActionService.dispatch("Delete OldCategory", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("OldCategory");
    }

    @Test
    void listCategories_returnsActionWithList() {
        String paramsJson = "{}";
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_categories", paramsJson));
        when(categoryService.findAll(any())).thenReturn(List.of(
                new CategoryResponse(1L, "Uncategorized", null),
                new CategoryResponse(2L, "Food", "utensils")
        ));

        ChatResponse resp = chatActionService.dispatch("Show my categories", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("2");
        assertThat(resp.result()).isInstanceOf(List.class);
    }

    // --- Budget update (direct tool) ---

    @Test
    void updateBudget_byId_returnsAction() {
        String paramsJson = """
                {"id":42,"amountLimit":5000}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_budget", paramsJson));
        var budget = new com.teng.app.gastosai.entity.Budget();
        budget.setCategory(Category.builder().id(3L).name("Food").build());
        budget.setMonth("2026-06");
        when(budgetRepository.findByIdAndUser(anyLong(), any())).thenReturn(Optional.of(budget));
        when(budgetService.update(anyLong(), any(), any())).thenReturn(
                new com.teng.app.gastosai.dto.BudgetResponse(42L, 3L, "Food", "2026-06",
                        new BigDecimal("5000.00"), "PHP", BigDecimal.ONE, new BigDecimal("5000.00")));

        ChatResponse resp = chatActionService.dispatch("Update budget 42 to 5000", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("5000");
    }

    // --- Goal update ---

    @Test
    void updateGoal_byName_returnsAction() {
        String paramsJson = """
                {"name":"Emergency Fund","targetAmount":20000}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_goal", paramsJson));
        SavingsGoal existingGoal = SavingsGoal.builder()
                .id(1L).user(user()).name("Emergency Fund")
                .targetAmount(new BigDecimal("10000")).savedAmount(BigDecimal.ZERO)
                .paused(false).currency("PHP").build();
        when(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(any())).thenReturn(List.of(existingGoal));
        when(savingsGoalService.update(anyLong(), any(), any())).thenReturn(
                new GoalResponse(1L, "Emergency Fund", new BigDecimal("20000.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, null, GoalStatus.ON_TRACK, false, LocalDateTime.now(), "PHP"));

        ChatResponse resp = chatActionService.dispatch("Update Emergency Fund goal to 20000", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("Emergency Fund");
    }

    @Test
    void updateGoal_notFound_returnsText() {
        String paramsJson = """
                {"name":"Ghost Goal","targetAmount":5000}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_goal", paramsJson));
        when(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(any())).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Update ghost goal", null, user());

        assertThat(resp.type()).isEqualTo("text");
    }

    // --- Recurring update ---

    @Test
    void updateRecurring_byName_pauseViaActive_returnsAction() {
        String paramsJson = """
                {"name":"Netflix","active":false}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_recurring", paramsJson));
        Category cat = Category.builder().id(1L).name("Utilities").build();
        RecurringExpense existing = RecurringExpense.builder()
                .id(10L).user(user()).name("Netflix").amount(new BigDecimal("499"))
                .frequency(Frequency.MONTHLY).category(cat).active(true)
                .currency("PHP").exchangeRate(BigDecimal.ONE).build();
        when(recurringExpenseRepository.findAllByUser(any())).thenReturn(List.of(existing));
        when(recurringExpenseService.update(anyLong(), any(), any())).thenReturn(
                new RecurringExpenseResponse(10L, "Netflix", new BigDecimal("499.00"),
                        "Utilities", Frequency.MONTHLY, null, null, null, false, "PHP", BigDecimal.ONE));

        ChatResponse resp = chatActionService.dispatch("Pause Netflix recurring", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("Netflix");
    }

    @Test
    void updateRecurring_notFound_returnsText() {
        String paramsJson = """
                {"name":"Ghost","amount":100}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_recurring", paramsJson));
        when(recurringExpenseRepository.findAllByUser(any())).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Update ghost recurring", null, user());

        assertThat(resp.type()).isEqualTo("text");
    }

    // --- Goal graceful duplicate on create ---

    @Test
    void createGoal_duplicate_returnsPreviewWithUpdateOffer() {
        String paramsJson = """
                {"name":"Emergency Fund","targetAmount":20000}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_goal", paramsJson));
        when(savingsGoalService.create(any(), any(), eq(false)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A goal named \"Emergency Fund\" already exists."));
        SavingsGoal existingGoal = SavingsGoal.builder()
                .id(1L).user(user()).name("Emergency Fund")
                .targetAmount(new BigDecimal("10000")).savedAmount(BigDecimal.ZERO)
                .paused(false).currency("PHP").build();
        when(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(any())).thenReturn(List.of(existingGoal));

        ChatResponse resp = chatActionService.dispatch("Create Emergency Fund goal ₱20000", "execute", user());

        assertThat(resp.type()).isEqualTo("preview");
        assertThat(resp.message()).containsIgnoringCase("Emergency Fund");
        assertThat(resp.result()).isNotNull();
        assertThat(resp.result()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) resp.result();
        assertThat(data.get("toolName")).isEqualTo("update_goal");
    }

    // --- Recurring graceful duplicate on create ---

    @Test
    void createRecurring_duplicate_returnsPreviewWithUpdateOffer() {
        String paramsJson = """
                {"name":"Rent","amount":5000,"frequency":"MONTHLY"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_recurring", paramsJson));
        when(recurringExpenseService.create(any(), any(), eq(false)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A monthly recurring expense named \"Rent\" already exists."));
        Category cat = Category.builder().id(1L).name("Utilities").build();
        RecurringExpense existing = RecurringExpense.builder()
                .id(5L).user(user()).name("Rent").amount(new BigDecimal("4500"))
                .frequency(Frequency.MONTHLY).category(cat).active(true)
                .currency("PHP").exchangeRate(BigDecimal.ONE).build();
        when(recurringExpenseRepository.findAllByUser(any())).thenReturn(List.of(existing));

        ChatResponse resp = chatActionService.dispatch("Create Rent recurring ₱5000 monthly", "execute", user());

        assertThat(resp.type()).isEqualTo("preview");
        assertThat(resp.message()).containsIgnoringCase("Rent");
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) resp.result();
        assertThat(data.get("toolName")).isEqualTo("update_recurring");
    }

    // --- Profile update ---

    @Test
    void updateProfile_returnsAction() {
        String paramsJson = """
                {"name":"New Name","nickname":"newnick"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("update_profile", paramsJson));
        when(userProfileService.updateProfile(anyString(), any())).thenReturn(
                new UserProfileResponse("u@test.com", "New Name", "newnick", null, null, null));

        ChatResponse resp = chatActionService.dispatch("Change my name to New Name", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("profile updated");
    }

    // --- Subscription read ---

    @Test
    void getSubscription_returnsActionWithPlanData() {
        String paramsJson = "{}";
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("get_subscription", paramsJson));
        when(entitlementService.describe(any())).thenReturn(
                new EntitlementService.Entitlements(PlanKey.FREE, SubscriptionStatus.INACTIVE, EnumSet.noneOf(com.teng.app.gastosai.entity.FeatureKey.class), false));

        ChatResponse resp = chatActionService.dispatch("What's my plan?", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("free");
        assertThat(resp.result()).isNotNull();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) resp.result();
        assertThat(data.get("plan")).isEqualTo("FREE");
    }

    // --- Expense dedup ---

    @Test
    void createExpense_duplicateWithin7Days_returnsDisambiguate() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));
        Expense duplicate = Expense.builder()
                .id(99L)
                .description("Lunch")
                .amount(new BigDecimal("500"))
                .date(LocalDateTime.now().minusDays(1))
                .build();
        when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of(duplicate));

        ChatResponse resp = chatActionService.dispatch("Add lunch ₱500", "execute", user());

        assertThat(resp.type()).isEqualTo("disambiguate");
        assertThat(resp.message()).containsIgnoringCase("duplicate");
        assertThat(resp.message()).containsIgnoringCase("Lunch");
    }

    @Test
    void createExpense_noDuplicate_createsExpense() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));
        when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of());
        when(expenseService.create(any(), any())).thenReturn(null);

        ChatResponse resp = chatActionService.dispatch("Add lunch ₱500", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("created");
    }

    @Test
    void createExpense_differentAmountSameDesc_notConsideredDuplicate() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));
        Expense notDupe = Expense.builder()
                .id(88L)
                .description("Lunch")
                .amount(new BigDecimal("300"))
                .date(LocalDateTime.now().minusDays(1))
                .build();
        when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of(notDupe));
        when(expenseService.create(any(), any())).thenReturn(null);

        ChatResponse resp = chatActionService.dispatch("Add lunch ₱500", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
    }

    @Test
    void createExpense_forceMode_skipsDedupAndCreates() {
        String paramsJson = """
                {"amount":500,"description":"Lunch","category":"Food"}
                """;
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));
        Expense duplicate = Expense.builder()
                .id(99L)
                .description("Lunch")
                .amount(new BigDecimal("500"))
                .date(LocalDateTime.now().minusDays(1))
                .build();
        lenient().when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of(duplicate));
        when(expenseService.create(any(), any())).thenReturn(null);

        ChatResponse resp = chatActionService.dispatch("Add lunch ₱500", "force", user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("created");
        verify(expenseRepository, never()).findByUserAndDateAfterOrderByDateDesc(any(), any());
    }
}
