package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Project;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.ProjectRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.ai.allow-shared-key=true",
        "gastos.monetization.enforce=false"
})
// Deliberately NOT @Transactional, unlike most integration tests here. Every /ai/chat request
// meters through AiUsageService.record, which is Propagation.REQUIRES_NEW — a separate transaction
// that cannot see rows an enclosing test transaction has not committed. The users this class
// creates in setUp() are exactly such rows, so the metering insert violated fk_ai_usage_user,
// marked the test transaction rollback-only, and the resulting failure opened the LLM circuit
// breaker for the eight tests that followed.
//
// This passed under H2 only because ai_usage.user_id is mapped as a plain Long, not a @ManyToOne:
// the ddl-auto=create-drop schema Hibernate generated for itself had no foreign key there at all,
// while the migrations that own the real schema do. Committing the fixture removes the divergence;
// isolation still comes from PostgresBackedTest's truncation, which does not need a shared
// transaction to work.
class ChatActionServiceIntegrationTest extends PostgresBackedTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired SavingsGoalRepository savingsGoalRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired ExpenseRepository expenseRepository;
    // Spied so the TEN-323 test can see which transaction the write runs in. The spy replaces the
    // target inside the transactional proxy and delegates to it, so every other test is unaffected.
    @MockitoSpyBean com.teng.app.gastosai.service.ExpenseService expenseService;
    // Spied for the same reason, so the goal path is pinned too and not only the expense one.
    @MockitoSpyBean com.teng.app.gastosai.service.SavingsGoalService savingsGoalService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    // Spied, not merely autowired, for the same reason as the two above: the TEN-324 tests need to
    // see which transaction the category writes run in, and to make one of them refuse.
    @MockitoSpyBean com.teng.app.gastosai.service.CategoryService categoryService;
    @MockitoSpyBean com.teng.app.gastosai.service.BudgetService budgetService;
    @MockitoSpyBean com.teng.app.gastosai.service.RecurringExpenseService recurringExpenseService;
    @MockitoSpyBean com.teng.app.gastosai.service.UserProfileService userProfileService;

    @MockitoBean SqlGenerator sqlGenerator;

    ObjectMapper objectMapper = new ObjectMapper();
    MockMvc mockMvc;
    String authHeaderUser1;
    String authHeaderUser2;
    User user1;
    User user2;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        user1 = userRepository.save(User.builder()
                .name("Chat Test User1")
                // Set so the TEN-325 tests can tell a preserved field from an unset one: the
                // principal must carry a value that a stale write would restore.
                .nickname("nick-at-login")
                .email("chattest1@test.com")
                .password(passwordEncoder.encode("password"))
                .role(Role.USER)
                .build());

        user2 = userRepository.save(User.builder()
                .name("Chat Test User2")
                .email("chattest2@test.com")
                .password(passwordEncoder.encode("password"))
                .role(Role.USER)
                .build());

        authHeaderUser1 = "Bearer " + jwtUtil.generate(user1.getEmail());
        authHeaderUser2 = "Bearer " + jwtUtil.generate(user2.getEmail());
    }

    @Test
    void listGoals_happyPath_returnsUserGoals() throws Exception {
        savingsGoalRepository.save(SavingsGoal.builder()
                .user(user1)
                .name("Emergency Fund")
                .targetAmount(new BigDecimal("50000"))
                .savedAmount(new BigDecimal("10000"))
                .paused(false)
                .currency("PHP")
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("list_goals", "{}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"show my goals\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"))
                .andExpect(jsonPath("$.result[0].name").value("Emergency Fund"))
                .andExpect(jsonPath("$.result[0].progressPercent").exists())
                .andExpect(jsonPath("$.result[0].status").exists());
    }

    @Test
    void listGoals_userIsolation_doesNotReturnOtherUsersGoals() throws Exception {
        savingsGoalRepository.save(SavingsGoal.builder()
                .user(user2)
                .name("User2 Goal")
                .targetAmount(new BigDecimal("10000"))
                .savedAmount(BigDecimal.ZERO)
                .paused(false)
                .currency("PHP")
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("list_goals", "{}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"show my goals\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result.length()").value(0));
    }

    @Test
    void listAlerts_happyPath_returnsAlerts() throws Exception {
        alertRepository.save(Alert.builder()
                .user(user1)
                .type(AlertType.BUDGET_WARNING)
                .severity(AlertSeverity.WARNING)
                .month("2026-06")
                .categoryName("Food")
                .message("Approaching budget limit for Food.")
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("list_alerts", "{\"month\":\"2026-06\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"show my alerts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"))
                .andExpect(jsonPath("$.result[0].severity").value("WARNING"));
    }

    @Test
    void markAlertRead_happyPath_marksAlertAndReturnsAction() throws Exception {
        Alert saved = alertRepository.save(Alert.builder()
                .user(user1)
                .type(AlertType.BUDGET_WARNING)
                .severity(AlertSeverity.WARNING)
                .month("2026-06")
                .categoryName("Food")
                .message("msg")
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("mark_alert_read", "{\"id\":" + saved.getId() + "}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"mark alert as read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));
    }

    @Test
    void markAlertRead_anotherUsersAlert_returnsNotFoundText() throws Exception {
        Alert saved = alertRepository.save(Alert.builder()
                .user(user2)
                .type(AlertType.BUDGET_WARNING)
                .severity(AlertSeverity.WARNING)
                .month("2026-06")
                .categoryName("Food")
                .message("msg")
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("mark_alert_read", "{\"id\":" + saved.getId() + "}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"mark alert as read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value("I couldn't find that item."));
    }

    @Test
    void deleteExpenses_withoutExecuteMode_returnsPreview() throws Exception {
        Category food = categoryRepository.findByUserAndNameIgnoreCase(user1, "Food")
                .orElseGet(() -> categoryNamed("Food"));
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Test Lunch")
                .amountInBaseCurrency(new BigDecimal("100"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expenses", "{\"ids\":[" + e.getId() + "]}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete all food expenses\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("preview"));
    }

    @Test
    void deleteExpenses_withExecuteMode_deletesAndReturnsCount() throws Exception {
        Category food = categoryRepository.findByUserAndNameIgnoreCase(user1, "Food")
                .orElseGet(() -> categoryNamed("Food"));
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Lunch to delete")
                .amountInBaseCurrency(new BigDecimal("100"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expenses", "{\"ids\":[" + e.getId() + "]}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete expenses\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"))
                .andExpect(jsonPath("$.result.deleted").value(1));
    }

    @Test
    void deleteExpenses_anotherUsersIds_deletesNothing() throws Exception {
        Category food = categoryRepository.findByUserAndNameIgnoreCase(user2, "Food")
                .orElseGet(() -> categoryRepository.save(Category.builder().name("Food").user(user2).build()));
        Expense othersExpense = expenseRepository.save(Expense.builder()
                .user(user2)
                .amount(new BigDecimal("100"))
                .category(food)
                .date(LocalDateTime.now())
                .description("User2 expense")
                .amountInBaseCurrency(new BigDecimal("100"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expenses", "{\"ids\":[" + othersExpense.getId() + "]}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete expenses\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"))
                .andExpect(jsonPath("$.result.deleted").value(0));

        // user2's expense is untouched
        org.junit.jupiter.api.Assertions.assertTrue(expenseRepository.findById(othersExpense.getId()).isPresent());
    }

    @Test
    void deleteExpenses_noIdsNoFilters_asksToNarrow() throws Exception {
        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expenses", "{}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete my expenses\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("which expenses")));
    }

    /**
     * TEN-317: a chat edit states only the fields the user named, and the update_expense tool
     * schema has no currency, exchange-rate or project property at all. Before the fix, editing
     * the description of a $100 expense redenominated it to ₱100 at rate 1 and dropped its tag.
     */
    @Test
    void updateExpense_foreignCurrency_preservesCurrencyRateBaseAmountAndProject() throws Exception {
        Category food = categoryRepository.findByUserAndNameIgnoreCase(user1, "Food")
                .orElseGet(() -> categoryNamed("Food"));
        Project acme = projectRepository.save(Project.builder().name("Acme").user(user1).build());
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100.0000"))
                .category(food)
                .project(acme)
                .date(LocalDateTime.now())
                .description("Dinner")
                .currency("USD")
                .exchangeRate(new BigDecimal("56.500000"))
                .amountInBaseCurrency(new BigDecimal("5650.0000"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_expense",
                        "{\"id\":" + e.getId() + ",\"amount\":100,\"description\":\"Dinner in NYC\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"rename that dinner to Dinner in NYC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        Expense reloaded = expenseRepository.findById(e.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getDescription()).isEqualTo("Dinner in NYC");
        org.assertj.core.api.Assertions.assertThat(reloaded.getCurrency()).isEqualTo("USD");
        org.assertj.core.api.Assertions.assertThat(reloaded.getExchangeRate())
                .isEqualByComparingTo(new BigDecimal("56.500000"));
        org.assertj.core.api.Assertions.assertThat(reloaded.getAmountInBaseCurrency())
                .isEqualByComparingTo(new BigDecimal("5650.0000"));
        org.assertj.core.api.Assertions.assertThat(reloaded.getProject()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(reloaded.getProject().getName()).isEqualTo("Acme");
    }

    /**
     * TEN-322: the same defect one field over. The update_expense schema requires only id, amount
     * and description, so an edit that only renames an expense carries no category — and reading
     * that as "Uncategorized" moved the expense out of the category the user had put it in.
     */
    @Test
    void updateExpense_noCategoryInParams_leavesCategoryUnchanged() throws Exception {
        Category food = categoryNamed("Food");
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("500.0000"))
                .amountInBaseCurrency(new BigDecimal("500.0000"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Dinner")
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_expense",
                        "{\"id\":" + e.getId() + ",\"amount\":500,\"description\":\"Dinner with Mika\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"rename that dinner to Dinner with Mika\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        Expense reloaded = expenseRepository.findById(e.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getDescription()).isEqualTo("Dinner with Mika");
        org.assertj.core.api.Assertions.assertThat(reloaded.getCategory().getName()).isEqualTo("Food");
    }

    /**
     * TEN-322: re-stating the row's own category must not turn into a hand-categorisation. Renaming
     * a Food expense to a description a merchant rule assigns elsewhere is not the user overriding
     * that rule, so categoryOverridden stays as it was.
     */
    @Test
    void updateExpense_noCategoryInParams_doesNotFlipCategoryOverridden() throws Exception {
        Category food = categoryNamed("Food");
        Category transport = categoryNamed("Transport");
        categoryService.learnMerchantRule("Grab ride", transport, user1);

        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("300.0000"))
                .amountInBaseCurrency(new BigDecimal("300.0000"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Dinner")
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .categoryOverridden(false)
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_expense",
                        "{\"id\":" + e.getId() + ",\"amount\":300,\"description\":\"Grab ride\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"that dinner was actually a Grab ride\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        Expense reloaded = expenseRepository.findById(e.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getCategory().getName()).isEqualTo("Food");
        org.assertj.core.api.Assertions.assertThat(reloaded.isCategoryOverridden()).isFalse();
        // The rule the user never argued with is still the rule.
        org.assertj.core.api.Assertions.assertThat(
                        categoryService.resolveByMerchant("Grab ride", user1).orElseThrow().getId())
                .isEqualTo(transport.getId());
    }

    /**
     * TEN-322: a stated category still goes through categorise untouched — it is applied, and
     * contradicting a merchant rule still records the override on the row without rewriting the
     * rule.
     */
    @Test
    void updateExpense_statedCategory_appliesItAndRecordsTheOverride() throws Exception {
        Category food = categoryNamed("Food");
        Category groceries = categoryNamed("Groceries");
        categoryService.learnMerchantRule("Jollibee", food, user1);

        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("250.0000"))
                .amountInBaseCurrency(new BigDecimal("250.0000"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Jollibee")
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_expense",
                        "{\"id\":" + e.getId() + ",\"amount\":250,\"description\":\"Jollibee\",\"category\":\"Groceries\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"that Jollibee was groceries\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        Expense reloaded = expenseRepository.findById(e.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getCategory().getId()).isEqualTo(groceries.getId());
        org.assertj.core.api.Assertions.assertThat(reloaded.isCategoryOverridden()).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                        categoryService.resolveByMerchant("Jollibee", user1).orElseThrow().getId())
                .isEqualTo(food.getId());
    }

    /**
     * TEN-323: the read that decides which values to carry forward and the write that applies them
     * are one transaction, not two.
     *
     * <p>What identifies the transaction is its name. The spy replaces the {@code ExpenseService}
     * target inside its transactional proxy, so the answer runs in whatever transaction the write
     * ended up in: if {@code update} started that transaction itself — the read having committed
     * before it — Spring names it {@code ExpenseService.update}, which is exactly what this test
     * observes when the handler's {@code inOneTransaction} wrapper is removed. Finding it unnamed
     * instead means the write joined a transaction opened programmatically further out, and the only
     * thing that opens one on this path is the handler, before its read: nothing in
     * {@code AiController.chat} → {@code dispatch} → {@code execute} is transactional.
     *
     * <p>One transaction also means one persistence context, so the entity the handler read and the
     * entity {@code update} loads are the same managed instance — the values re-stated from the read
     * are the values being written.
     */
    @Test
    void updateExpense_readAndWrite_shareOneTransaction() throws Exception {
        Category food = categoryNamed("Food");
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("400.0000"))
                .amountInBaseCurrency(new BigDecimal("400.0000"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Dinner")
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .build());

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(expenseService).update(org.mockito.ArgumentMatchers.eq(e.getId()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_expense",
                        "{\"id\":" + e.getId() + ",\"amount\":400,\"description\":\"Dinner with Ana\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"rename that dinner to Dinner with Ana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
        org.assertj.core.api.Assertions.assertThat(
                        expenseRepository.findById(e.getId()).orElseThrow().getDescription())
                .isEqualTo("Dinner with Ana");
    }

    /**
     * TEN-323, second wrapped handler. The expense path was the only one pinned, so deleting
     * {@code inOneTransaction} from the budget, goal or recurring handler left the suite green —
     * the wrapper could be removed from three of the four without anything noticing.
     *
     * <p>Same discriminator as above: an unnamed transaction at write time means the write joined
     * one opened programmatically further out, rather than the {@code @Transactional} on
     * {@code SavingsGoalService.update} starting its own after the read had already committed.
     */
    @Test
    void updateGoal_readAndWrite_shareOneTransaction() throws Exception {
        SavingsGoal goal = savingsGoalRepository.save(SavingsGoal.builder()
                .user(user1)
                .name("Laptop Fund")
                .targetAmount(new BigDecimal("50000"))
                .savedAmount(new BigDecimal("1000"))
                .paused(false)
                .currency("PHP")
                .build());

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(savingsGoalService).update(org.mockito.ArgumentMatchers.eq(goal.getId()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_goal",
                        "{\"id\":" + goal.getId() + ",\"targetAmount\":60000}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"change my Laptop Fund target to 60000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
        org.assertj.core.api.Assertions.assertThat(
                        savingsGoalRepository.findById(goal.getId()).orElseThrow().getTargetAmount())
                .isEqualByComparingTo("60000");
    }

    /**
     * TEN-325: a chat profile update must not revert a field changed elsewhere during the same
     * request.
     *
     * <p>The out-of-band write happens inside the {@code classifyIntent} stub, which is exactly
     * the window the bug lived in: {@code JwtAuthFilter} has already loaded and detached the
     * principal, and the handler has not run yet. Before the fix the handler restated
     * {@code nickname} from that snapshot and wrote the pre-change value back.
     */
    @Test
    void updateProfile_doesNotRevertAFieldChangedOutOfBand() throws Exception {
        when(sqlGenerator.classifyIntent(anyString())).thenAnswer(invocation -> {
            User fresh = userRepository.findByEmail(user1.getEmail()).orElseThrow();
            fresh.setNickname("changed-from-mobile");
            userRepository.save(fresh);
            return LlmResult.ofValue(new ChatToolCall("update_profile", "{\"name\":\"Renamed By Chat\"}"));
        });

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"change my name to Renamed By Chat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        User after = userRepository.findByEmail(user1.getEmail()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getName()).isEqualTo("Renamed By Chat");
        org.assertj.core.api.Assertions.assertThat(after.getNickname())
                .as("a nickname changed out of band survives a chat update that never named it")
                .isEqualTo("changed-from-mobile");
    }

    /** TEN-325, the same guarantee for the blunter handler. */
    @Test
    void setDefaultCategory_doesNotRevertAFieldChangedOutOfBand() throws Exception {
        when(sqlGenerator.classifyIntent(anyString())).thenAnswer(invocation -> {
            User fresh = userRepository.findByEmail(user1.getEmail()).orElseThrow();
            fresh.setNickname("changed-from-mobile");
            userRepository.save(fresh);
            return LlmResult.ofValue(new ChatToolCall("set_default_category", "{\"categoryName\":\"Groceries\"}"));
        });

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"make Groceries my default category\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        User after = userRepository.findByEmail(user1.getEmail()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getDefaultCategoryName()).isEqualTo("Groceries");
        org.assertj.core.api.Assertions.assertThat(after.getNickname())
                .as("a nickname changed out of band survives set_default_category")
                .isEqualTo("changed-from-mobile");
    }

    // --- TEN-324: the destructive handlers run in one transaction ---

    /**
     * TEN-324, the headline case: a bulk delete that fails partway deletes nothing.
     *
     * <p>Before the fix the handler looped {@code expenseService.delete} outside any transaction of
     * its own — its {@code @Transactional} was inert — so the deletes that had already run were
     * committed one by one and stayed gone, with no record of which.
     */
    @Test
    void deleteExpenses_failurePartway_deletesNothing() throws Exception {
        Expense first = expenseNamed("First");
        Expense second = expenseNamed("Second");
        Expense third = expenseNamed("Third");

        org.mockito.Mockito.doThrow(new IllegalStateException("delete failed partway"))
                .when(expenseService).delete(org.mockito.ArgumentMatchers.eq(second.getId()),
                        org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expenses",
                        "{\"ids\":[" + first.getId() + "," + second.getId() + "," + third.getId() + "]}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete those three\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("went wrong")));

        org.assertj.core.api.Assertions.assertThat(expenseRepository.findById(first.getId()))
                .as("the delete that ran before the failure is rolled back, not committed")
                .isPresent();
        org.assertj.core.api.Assertions.assertThat(expenseRepository.findById(second.getId())).isPresent();
        org.assertj.core.api.Assertions.assertThat(expenseRepository.findById(third.getId())).isPresent();
    }

    /**
     * TEN-324, the rollback-only hazard: {@code CategoryService.update} throws its duplicate-name
     * {@code IllegalArgumentException} from inside a live {@code @Transactional}, which marks the
     * shared transaction rollback-only. The catch is hoisted outside {@code inOneTransaction} so the
     * user still gets the friendly message instead of an {@code UnexpectedRollbackException}.
     */
    @Test
    void renameCategory_toADuplicateName_returnsTheFriendlyMessage() throws Exception {
        Category food = categoryNamed("Food");
        categoryNamed("Meals");

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("rename_category",
                        "{\"currentName\":\"Food\",\"newName\":\"Meals\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"rename Food to Meals\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));

        org.assertj.core.api.Assertions.assertThat(
                        categoryRepository.findById(food.getId()).orElseThrow().getName())
                .isEqualTo("Food");
    }

    /**
     * The same hazard on the second handler that carries it. {@code CategoryService.delete} refuses
     * only the {@code Uncategorized} category, which the handler short-circuits before the service
     * is reached, so the refusal is stubbed onto the spy — which sits <em>inside</em> the
     * transactional proxy, so the throw still travels out through it and still marks the shared
     * transaction rollback-only. That is the mechanic under test.
     */
    @Test
    void deleteCategory_refusedByTheService_returnsTheFriendlyMessage() throws Exception {
        Category food = categoryNamed("Food");

        org.mockito.Mockito.doThrow(new IllegalArgumentException("Default categories cannot be deleted"))
                .when(categoryService).delete(org.mockito.ArgumentMatchers.eq(food.getId()),
                        org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("delete_category", "{\"name\":\"Food\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete the Food category\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot be deleted")));

        org.assertj.core.api.Assertions.assertThat(categoryRepository.findById(food.getId())).isPresent();
    }

    /**
     * TEN-324, the write-then-write shape: {@code getOrCreateByName} used to commit the category in
     * its own transaction, so a failure in the write that follows left an empty category behind that
     * the user never asked for.
     */
    @Test
    void createBudget_whenTheBudgetWriteFails_doesNotStrandTheCategory() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("budget write failed"))
                .when(budgetService).create(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(com.teng.app.gastosai.entity.User.class));

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("create_budget",
                        "{\"categoryName\":\"Gadgets\",\"amountLimit\":5000,\"month\":\"2026-09\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"budget 5000 for Gadgets\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"));

        org.assertj.core.api.Assertions.assertThat(
                        categoryRepository.findByUserAndNameIgnoreCase(user1, "Gadgets"))
                .as("the category created for a budget that failed is rolled back with it")
                .isEmpty();
    }

    /** The same shape on {@code set_default_category}, whose second write is the profile patch. */
    @Test
    void setDefaultCategory_whenTheProfileWriteFails_doesNotStrandTheCategory() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("profile write failed"))
                .when(userProfileService).patchProfile(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("set_default_category",
                        "{\"categoryName\":\"Gadgets\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"make Gadgets my default\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"));

        org.assertj.core.api.Assertions.assertThat(
                        categoryRepository.findByUserAndNameIgnoreCase(user1, "Gadgets"))
                .isEmpty();
    }

    // --- TEN-324: the four handlers TEN-323 wrapped but left unpinned ---

    @Test
    void updateBudget_readAndWrite_shareOneTransaction() throws Exception {
        Category food = categoryNamed("Food");
        Object created = budgetService.create(
                new com.teng.app.gastosai.dto.BudgetRequest(food.getId(), "2026-09",
                        new BigDecimal("3000"), null, null, null), user1);
        long budgetId = ((com.teng.app.gastosai.dto.BudgetResponse) created).id();

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(budgetService).update(
                org.mockito.ArgumentMatchers.eq(budgetId),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_budget",
                        "{\"id\":" + budgetId + ",\"amountLimit\":4000}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"raise my food budget to 4000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
    }

    @Test
    void updateRecurring_readAndWrite_shareOneTransaction() throws Exception {
        categoryNamed("Entertainment");
        com.teng.app.gastosai.dto.RecurringExpenseResponse created = recurringExpenseService.create(
                new com.teng.app.gastosai.dto.RecurringExpenseRequest("Netflix", new BigDecimal("499"),
                        "Entertainment", com.teng.app.gastosai.entity.Frequency.MONTHLY, 15, null, null,
                        true, "PHP", BigDecimal.ONE), user1);

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(recurringExpenseService).update(
                org.mockito.ArgumentMatchers.eq(created.id()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("update_recurring",
                        "{\"id\":" + created.id() + ",\"amount\":599}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Netflix is 599 now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
    }

    @Test
    void setCategoryIcon_readAndWrite_shareOneTransaction() throws Exception {
        Category food = categoryNamed("Food");

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(categoryService).update(
                org.mockito.ArgumentMatchers.eq(food.getId()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("set_category_icon",
                        "{\"categoryName\":\"Food\",\"icon\":\"utensils\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"give Food a utensils icon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
    }

    /**
     * The recategorize wrapper exists to contain {@code getOrCreateByName}, so that is what is
     * probed: joined means the category it may create is rolled back with a failing
     * {@code saveAll} rather than committed on its own.
     */
    @Test
    void recategorizeExpenses_categoryCreationJoinsTheHandlersTransaction() throws Exception {
        Category food = categoryNamed("Food");
        expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100.0000"))
                .amountInBaseCurrency(new BigDecimal("100.0000"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Lunch")
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .build());

        TransactionProbe probe = new TransactionProbe();
        org.mockito.Mockito.doAnswer(probe).when(categoryService).getOrCreateByName(
                org.mockito.ArgumentMatchers.eq("Meals"), org.mockito.ArgumentMatchers.any());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(LlmResult.ofValue(new ChatToolCall("recategorize_expenses",
                        "{\"fromCategory\":\"Food\",\"toCategory\":\"Meals\"}")));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"move Food to Meals\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("action"));

        probe.assertTheWriteJoinedTheHandlersTransaction();
    }

    /**
     * Records which transaction a spied write ran in.
     *
     * <p>What identifies it is its name. A spy replaces the target inside its transactional proxy,
     * so the answer runs in whatever transaction the write ended up in: a write that started its own
     * — the read having committed before it — is named after the service method that started it,
     * where a write that joined a transaction opened programmatically further out is unnamed. The
     * only thing that opens one on this path is the handler, before its read: nothing in
     * {@code AiController.chat} → {@code dispatch} → {@code execute} is transactional.
     *
     * <p>One transaction also means one persistence context, so the entity the handler read and the
     * entity the write loads are the same managed instance.
     *
     * <p>Written for TEN-324, which pins the four handlers TEN-323 wrapped but never tested — each
     * could lose its wrapper with the suite still green — and replaces the two near-identical
     * inline blocks the TEN-323 tests carried.
     */
    private static final class TransactionProbe implements org.mockito.stubbing.Answer<Object> {

        private final java.util.concurrent.atomic.AtomicBoolean active =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<String> name =
                new java.util.concurrent.atomic.AtomicReference<>("not recorded");

        @Override
        public Object answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            active.set(TransactionSynchronizationManager.isActualTransactionActive());
            name.set(TransactionSynchronizationManager.getCurrentTransactionName());
            return invocation.callRealMethod();
        }

        void assertTheWriteJoinedTheHandlersTransaction() {
            org.assertj.core.api.Assertions.assertThat(active.get())
                    .as("the write runs in a transaction")
                    .isTrue();
            org.assertj.core.api.Assertions.assertThat(name.get())
                    .as("the write joined the handler's programmatic transaction instead of starting its own")
                    .isNull();
        }
    }

    /** An expense of user1's, for the tests that only care that it exists and can be deleted. */
    private Expense expenseNamed(String description) {
        return expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100.0000"))
                .amountInBaseCurrency(new BigDecimal("100.0000"))
                .category(categoryNamed("Food"))
                .date(LocalDateTime.now())
                .description(description)
                .currency("PHP")
                .exchangeRate(BigDecimal.ONE)
                .build());
    }

    /** Categories are per-user and may already be seeded, so take the existing row when there is one. */
    private Category categoryNamed(String name) {
        return categoryRepository.findByUserAndNameIgnoreCase(user1, name)
                .orElseGet(() -> categoryRepository.save(Category.builder().name(name).user(user1).build()));
    }
}
