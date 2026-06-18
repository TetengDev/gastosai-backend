package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.service.BudgetService;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.ChatActionService;
import com.teng.app.gastosai.service.ExpenseService;
import com.teng.app.gastosai.service.RecurringExpenseService;
import com.teng.app.gastosai.service.SavingsGoalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatActionServiceTest {

    @Mock SqlGenerator sqlGenerator;
    @Mock ExpenseService expenseService;
    @Mock BudgetService budgetService;
    @Mock SavingsGoalService savingsGoalService;
    @Mock RecurringExpenseService recurringExpenseService;
    @Mock CategoryService categoryService;
    @Mock ExpenseRepository expenseRepository;
    @Mock RecurringExpenseRepository recurringExpenseRepository;
    @Mock BudgetRepository budgetRepository;
    @Mock SavingsGoalRepository savingsGoalRepository;
    @Spy ObjectMapper objectMapper;

    @InjectMocks ChatActionService chatActionService;

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
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));

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
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("create_expense", paramsJson));

        ExpenseResponse mockResult = new ExpenseResponse(
                1L, new BigDecimal("500.00"), "Food",
                LocalDateTime.now(), "Lunch", "PERSONAL", false, "PHP",
                BigDecimal.ONE, new BigDecimal("500.00"));
        when(expenseService.create(any(), any())).thenReturn(mockResult);

        ChatResponse response = chatActionService.dispatch("Add lunch ₱500", "execute", user());

        assertThat(response.type()).isEqualTo("action");
        assertThat(response.message()).contains("Expense created");
        assertThat(response.message()).contains("500");
        assertThat(response.message()).contains("Lunch");
        assertThat(response.result()).isEqualTo(mockResult);
    }

    @Test
    void dispatch_textFallback_returnsTextResponse() {
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("text", "Hello there!"));

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
        when(sqlGenerator.classifyIntent(any())).thenReturn(new ChatToolCall("delete_expense", paramsJson));
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
}
