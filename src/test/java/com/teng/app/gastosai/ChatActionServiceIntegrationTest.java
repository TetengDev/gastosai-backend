package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
class ChatActionServiceIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired SavingsGoalRepository savingsGoalRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

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
                .thenReturn(new ChatToolCall("list_goals", "{}"));

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
                .thenReturn(new ChatToolCall("list_goals", "{}"));

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
                .thenReturn(new ChatToolCall("list_alerts", "{\"month\":\"2026-06\"}"));

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
                .thenReturn(new ChatToolCall("mark_alert_read", "{\"id\":" + saved.getId() + "}"));

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
                .thenReturn(new ChatToolCall("mark_alert_read", "{\"id\":" + saved.getId() + "}"));

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
                .orElseGet(() -> categoryRepository.save(Category.builder().name("Food").user(user1).build()));
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Test Lunch")
                .amountInBaseCurrency(new BigDecimal("100"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(new ChatToolCall("delete_expenses", "{\"ids\":[" + e.getId() + "]}"));

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
                .orElseGet(() -> categoryRepository.save(Category.builder().name("Food").user(user1).build()));
        Expense e = expenseRepository.save(Expense.builder()
                .user(user1)
                .amount(new BigDecimal("100"))
                .category(food)
                .date(LocalDateTime.now())
                .description("Lunch to delete")
                .amountInBaseCurrency(new BigDecimal("100"))
                .build());

        when(sqlGenerator.classifyIntent(anyString()))
                .thenReturn(new ChatToolCall("delete_expenses", "{\"ids\":[" + e.getId() + "]}"));

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
                .thenReturn(new ChatToolCall("delete_expenses", "{\"ids\":[" + othersExpense.getId() + "]}"));

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
                .thenReturn(new ChatToolCall("delete_expenses", "{}"));

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeaderUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete my expenses\",\"mode\":\"execute\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("which expenses")));
    }
}
