package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.dto.BudgetSummaryItem;
import com.teng.app.gastosai.dto.BudgetSummaryResponse;
import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.GoalStatus;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatActionServiceReadToolsTest {

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

    @InjectMocks ChatActionService chatActionService;

    @BeforeEach
    void setUp() {
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
                .name("Test")
                .password("x")
                .build();
    }

    // --- list_goals ---

    @Test
    void listGoals_returnsActionWithGoalShape() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_goals", "{}"));
        GoalResponse goal = new GoalResponse(1L, "Emergency Fund", new BigDecimal("10000.00"),
                new BigDecimal("3000.00"), new BigDecimal("30.00"), null, GoalStatus.ON_TRACK,
                false, LocalDateTime.now(), "PHP");
        when(savingsGoalService.findAll(any())).thenReturn(List.of(goal));

        ChatResponse resp = chatActionService.dispatch("Show my goals", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        Map<String, Object> first = items.get(0);
        assertThat(first).containsKeys("id", "name", "targetAmount", "savedAmount", "progressPercent", "status");
        assertThat(first.get("progressPercent")).isEqualTo(new BigDecimal("30.00"));
    }

    @Test
    void listGoals_emptyList_returnsZeroGoals() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_goals", "{}"));
        when(savingsGoalService.findAll(any())).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Show my goals", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("0");
    }

    // --- list_budgets ---

    @Test
    void listBudgets_returnsActionWithBudgetSummaryShape() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_budgets", "{\"month\":\"2026-06\"}"));
        BudgetSummaryItem item = new BudgetSummaryItem(1L, "Food", new BigDecimal("5000.00"),
                new BigDecimal("3000.00"), new BigDecimal("2000.00"), new BigDecimal("60.00"), "ON_TRACK");
        BudgetSummaryResponse summary = new BudgetSummaryResponse("2026-06", List.of(item),
                new BigDecimal("5000.00"), new BigDecimal("3000.00"), new BigDecimal("2000.00"), new BigDecimal("100.00"));
        when(budgetService.getSummary(anyString(), any())).thenReturn(summary);

        ChatResponse resp = chatActionService.dispatch("Show budgets", null, user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKeys("month", "totalBudgeted", "totalSpent", "safeToSpend", "items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKeys("categoryName", "budgeted", "spent", "remaining", "percentUsed", "status");
    }

    // --- list_alerts ---

    @Test
    void listAlerts_returnsActionWithAlertShape() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_alerts", "{\"month\":\"2026-06\"}"));
        AlertResponse alert = new AlertResponse(5L, AlertType.BUDGET_WARNING, AlertSeverity.WARNING,
                "2026-06", "Food", "Approaching budget limit for Food.", false, false, LocalDateTime.now(), null);
        when(alertService.getOrGenerate(any(), anyString())).thenReturn(List.of(alert));

        ChatResponse resp = chatActionService.dispatch("Show my alerts", null, user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        Map<String, Object> first = items.get(0);
        assertThat(first).containsKeys("id", "type", "severity", "message", "read");
        assertThat(first.get("severity")).isEqualTo("WARNING");
    }

    // --- search_expenses ---

    @Test
    void searchExpenses_noFilters_returnsCappedList() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("search_expenses", "{}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Expense e = Expense.builder().id(10L).amount(new BigDecimal("250"))
                .category(food).date(LocalDateTime.now()).description("Lunch").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(e));

        ChatResponse resp = chatActionService.dispatch("Search my expenses", null, user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        Map<String, Object> first = items.get(0);
        assertThat(first).containsKeys("id", "amount", "category", "date", "description");
        assertThat(first.get("category")).isEqualTo("Food");
    }

    @Test
    void searchExpenses_categoryFilter_appliesInMemoryFilter() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("search_expenses", "{\"category\":\"Food\"}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Category transport = Category.builder().id(2L).name("Transportation").build();
        Expense e1 = Expense.builder().id(1L).amount(new BigDecimal("100"))
                .category(food).date(LocalDateTime.now()).description("Lunch").build();
        Expense e2 = Expense.builder().id(2L).amount(new BigDecimal("50"))
                .category(transport).date(LocalDateTime.now()).description("Grab").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(e1, e2));

        ChatResponse resp = chatActionService.dispatch("Search food expenses", null, user());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("category")).isEqualTo("Food");
    }

    @Test
    void searchExpenses_vendorFilter_appliesIgnoreCaseFilter() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("search_expenses", "{\"vendor\":\"jollibee\"}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Expense e1 = Expense.builder().id(1L).amount(new BigDecimal("200"))
                .category(food).date(LocalDateTime.now()).description("Jollibee").build();
        Expense e2 = Expense.builder().id(2L).amount(new BigDecimal("300"))
                .category(food).date(LocalDateTime.now()).description("McDonald's").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(e1, e2));

        ChatResponse resp = chatActionService.dispatch("Jollibee expenses", null, user());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("description")).isEqualTo("Jollibee");
    }

    @Test
    void searchExpenses_amountRangeFilter_appliesFilter() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("search_expenses", "{\"minAmount\":100,\"maxAmount\":300}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Expense cheap = Expense.builder().id(1L).amount(new BigDecimal("50"))
                .category(food).date(LocalDateTime.now()).description("Snack").build();
        Expense mid = Expense.builder().id(2L).amount(new BigDecimal("200"))
                .category(food).date(LocalDateTime.now()).description("Lunch").build();
        Expense expensive = Expense.builder().id(3L).amount(new BigDecimal("500"))
                .category(food).date(LocalDateTime.now()).description("Dinner").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(cheap, mid, expensive));

        ChatResponse resp = chatActionService.dispatch("Mid-range expenses", null, user());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("description")).isEqualTo("Lunch");
    }

    // --- get_category_totals ---

    @Test
    void getCategoryTotals_withMonth_callsCategoryReportForMonth() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("get_category_totals", "{\"month\":\"2026-06\"}"));
        when(expenseService.categoryReportForMonth(any(), anyString())).thenReturn(
                List.of(new CategoryReportItem("Food", new BigDecimal("3000.00"))));

        ChatResponse resp = chatActionService.dispatch("Category totals for June", null, user());

        assertThat(resp.type()).isEqualTo("action");
        verify(expenseService).categoryReportForMonth(any(), eq("2026-06"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
        assertThat(items.get(0)).containsKeys("category", "total");
    }

    @Test
    void getCategoryTotals_withoutMonth_callsAllTimeCategoryReport() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("get_category_totals", "{}"));
        when(expenseService.categoryReport(any())).thenReturn(
                List.of(new CategoryReportItem("Food", new BigDecimal("15000.00"))));

        ChatResponse resp = chatActionService.dispatch("All-time category totals", null, user());

        assertThat(resp.type()).isEqualTo("action");
        verify(expenseService).categoryReport(any());
    }

    // --- get_monthly_report ---

    @Test
    void getMonthlyReport_returnsReportShape() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("get_monthly_report", "{\"month\":\"2026-06\"}"));
        when(expenseService.categoryReportForMonth(any(), anyString())).thenReturn(
                List.of(new CategoryReportItem("Food", new BigDecimal("3000.00"))));
        Category food = Category.builder().id(1L).name("Food").build();
        Expense e = Expense.builder().id(1L).amount(new BigDecimal("3000"))
                .category(food).date(LocalDateTime.now()).description("Groceries").build();
        when(expenseRepository.findByUserAndDateBetweenOrderByAmountDesc(any(), any(), any(), any()))
                .thenReturn(List.of(e));

        ChatResponse resp = chatActionService.dispatch("Monthly report June", null, user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKeys("month", "totalSpent", "categoryBreakdown", "topExpenses");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breakdown = (List<Map<String, Object>>) result.get("categoryBreakdown");
        assertThat(breakdown.get(0)).containsKeys("category", "total");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topExpenses = (List<Map<String, Object>>) result.get("topExpenses");
        assertThat(topExpenses.get(0)).containsKeys("id", "amount", "category", "date", "description");
    }

    // --- mark_alert_read ---

    @Test
    void markAlertRead_callsAlertServiceAndReturnsAction() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("mark_alert_read", "{\"id\":5}"));
        AlertResponse alertResp = new AlertResponse(5L, AlertType.BUDGET_WARNING, AlertSeverity.WARNING,
                "2026-06", "Food", "msg", true, false, LocalDateTime.now(), null);
        when(alertService.markRead(anyLong(), any())).thenReturn(alertResp);

        ChatResponse resp = chatActionService.dispatch("Mark alert 5 as read", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).contains("5");
        verify(alertService).markRead(eq(5L), any());
    }

    // --- dismiss_alert ---

    @Test
    void dismissAlert_callsAlertServiceAndReturnsAction() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("dismiss_alert", "{\"id\":3}"));
        AlertResponse alertResp = new AlertResponse(3L, AlertType.BUDGET_EXCEEDED, AlertSeverity.CRITICAL,
                "2026-06", "Food", "msg", false, true, LocalDateTime.now(), null);
        when(alertService.dismiss(anyLong(), any())).thenReturn(alertResp);

        ChatResponse resp = chatActionService.dispatch("Dismiss alert 3", null, user());

        assertThat(resp.type()).isEqualTo("action");
        verify(alertService).dismiss(eq(3L), any());
    }

    // --- delete_alert ---

    @Test
    void deleteAlert_callsAlertServiceDeleteAndReturnsAction() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("delete_alert", "{\"id\":7}"));

        ChatResponse resp = chatActionService.dispatch("Delete alert 7", null, user());

        assertThat(resp.type()).isEqualTo("action");
        verify(alertService).delete(eq(7L), any());
    }

    // --- set_default_category ---

    @Test
    void setDefaultCategory_callsProfileUpdateAndReturnsAction() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("set_default_category", "{\"categoryName\":\"Groceries\"}"));
        Category cat = Category.builder().id(2L).name("Groceries").build();
        when(categoryService.getOrCreateByName("Groceries")).thenReturn(cat);

        ChatResponse resp = chatActionService.dispatch("Set default category to Groceries", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("Groceries");
        verify(userProfileService).updateProfile(eq("u@test.com"), any());
    }

    // --- set_category_icon ---

    @Test
    void setCategoryIcon_updatesIconAndReturnsAction() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("set_category_icon", "{\"categoryName\":\"Food\",\"icon\":\"utensils\"}"));
        when(categoryService.findAll()).thenReturn(List.of(new CategoryResponse(1L, "Food", null)));
        when(categoryService.update(anyLong(), any())).thenReturn(new CategoryResponse(1L, "Food", "utensils"));

        ChatResponse resp = chatActionService.dispatch("Set food icon to utensils", null, user());

        assertThat(resp.type()).isEqualTo("action");
        assertThat(resp.message()).containsIgnoringCase("Food");
        assertThat(resp.result()).isInstanceOf(CategoryResponse.class);
    }

    @Test
    void setCategoryIcon_categoryNotFound_throwsResourceNotFound() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("set_category_icon", "{\"categoryName\":\"Ghost\",\"icon\":\"x\"}"));
        when(categoryService.findAll()).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Set ghost icon", null, user());

        assertThat(resp.type()).isEqualTo("text");
        assertThat(resp.message()).isEqualTo("I couldn't find that item.");
    }

    // --- delete_expenses (destructive gate) ---

    @Test
    void deleteExpenses_withoutExecuteMode_returnsPreview() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("delete_expenses", "{\"ids\":[1,2,3]}"));

        ChatResponse resp = chatActionService.dispatch("Delete expenses 1 2 3", null, user());

        assertThat(resp.type()).isEqualTo("preview");
        assertThat(resp.message()).containsIgnoringCase("delete");
        assertThat(resp.message()).containsIgnoringCase("cannot be undone");
    }

    @Test
    void deleteExpenses_byIdList_withExecuteMode_deletesAndReturnsCount() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("delete_expenses", "{\"ids\":[10,20]}"));

        ChatResponse resp = chatActionService.dispatch("Delete expenses", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKey("deleted");
        verify(expenseService).delete(eq(10L), any());
        verify(expenseService).delete(eq(20L), any());
    }

    @Test
    void deleteExpenses_byIdList_notFound_skippedAndCountsSuccessful() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("delete_expenses", "{\"ids\":[10]}"));

        ChatResponse resp = chatActionService.dispatch("Delete expenses", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKey("deleted");
        assertThat((int) result.get("deleted")).isEqualTo(1);
    }

    @Test
    void deleteExpenses_byFilter_withExecuteMode_deletesFilteredExpenses() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("delete_expenses", "{\"category\":\"Food\"}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Expense e = Expense.builder().id(42L).amount(new BigDecimal("100"))
                .category(food).date(LocalDateTime.now()).description("Lunch").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(e));

        ChatResponse resp = chatActionService.dispatch("Delete all food expenses", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        verify(expenseService).delete(eq(42L), any());
    }

    // --- recategorize_expenses (destructive gate) ---

    @Test
    void recategorizeExpenses_withoutExecuteMode_returnsPreview() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("recategorize_expenses", "{\"fromCategory\":\"Food\",\"toCategory\":\"Meals\"}"));

        ChatResponse resp = chatActionService.dispatch("Move food to meals", null, user());

        assertThat(resp.type()).isEqualTo("preview");
        assertThat(resp.message()).containsIgnoringCase("Food");
        assertThat(resp.message()).containsIgnoringCase("Meals");
    }

    @Test
    void recategorizeExpenses_withExecuteMode_updatesAndReturnsCount() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(
                new ChatToolCall("recategorize_expenses", "{\"fromCategory\":\"Food\",\"toCategory\":\"Meals\"}"));
        Category food = Category.builder().id(1L).name("Food").build();
        Category meals = Category.builder().id(2L).name("Meals").build();
        Expense e = Expense.builder().id(1L).amount(new BigDecimal("100"))
                .category(food).date(LocalDateTime.now()).description("Lunch").build();
        when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(e));
        when(categoryService.getOrCreateByName("Meals")).thenReturn(meals);
        when(expenseRepository.saveAll(any())).thenReturn(List.of(e));

        ChatResponse resp = chatActionService.dispatch("Move food to meals", "execute", user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat((int) result.get("updated")).isEqualTo(1);
    }

    // --- list_recurring ---

    @Test
    void listRecurring_returnsItemsAndUpcomingShape() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_recurring", "{}"));
        RecurringExpenseResponse r = new RecurringExpenseResponse(1L, "Netflix", new BigDecimal("499.00"),
                "Entertainment", Frequency.MONTHLY, 15, null, null, true, "PHP", BigDecimal.ONE);
        when(recurringExpenseService.findAll(any())).thenReturn(List.of(r));
        UpcomingBillResponse upcoming = new UpcomingBillResponse(1L, "Netflix", new BigDecimal("499.00"),
                "Entertainment", Frequency.MONTHLY, "2026-06-15", "PHP");
        when(recurringExpenseService.getUpcoming(anyString(), any())).thenReturn(List.of(upcoming));

        ChatResponse resp = chatActionService.dispatch("Show recurring", null, user());

        assertThat(resp.type()).isEqualTo("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKeys("items", "upcoming");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items.get(0)).containsKeys("id", "name", "amount", "categoryName", "frequency", "active");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upcomingList = (List<Map<String, Object>>) result.get("upcoming");
        assertThat(upcomingList.get(0)).containsKeys("name", "amount", "dueDate");
    }

    // --- user isolation: list_goals only returns caller's data ---

    @Test
    void listGoals_onlyReturnsCallerGoals() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("list_goals", "{}"));
        User caller = user();
        when(savingsGoalService.findAll(caller)).thenReturn(List.of());

        ChatResponse resp = chatActionService.dispatch("Show goals", null, caller);

        verify(savingsGoalService).findAll(caller);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) resp.result();
        assertThat(items).isEmpty();
    }
}
