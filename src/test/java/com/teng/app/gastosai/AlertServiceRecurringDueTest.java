package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.*;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.service.AlertService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceRecurringDueTest {

    @Mock AlertRepository alertRepository;
    @Mock BudgetRepository budgetRepository;
    @Mock ExpenseRepository expenseRepository;
    @Mock RecurringExpenseRepository recurringExpenseRepository;

    @InjectMocks AlertService alertService;

    private static final String MONTH = "2026-06";

    private User user() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
    }

    private RecurringExpense monthlyExpense(int dayOfMonth) {
        return RecurringExpense.builder()
                .id(42L)
                .name("Netflix")
                .amount(new BigDecimal("199.0000"))
                .frequency(Frequency.MONTHLY)
                .dayOfMonth(dayOfMonth)
                .active(true)
                .build();
    }

    private void stubCommonGetOrGenerate(User u) {
        when(budgetRepository.findAllByUserAndMonth(u, MONTH)).thenReturn(List.of());
        when(expenseRepository.sumForMonth(any(), anyInt(), anyInt())).thenReturn(null);
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, MONTH))
                .thenReturn(List.of());
    }

    @Test
    void monthly_dueTodayInWindow_generatesAlert() {
        User u = user();
        int today = LocalDate.now().getDayOfMonth();
        RecurringExpense expense = monthlyExpense(today);

        stubCommonGetOrGenerate(u);
        when(recurringExpenseRepository.findAllByUser(u)).thenReturn(List.of(expense));
        when(alertRepository.findByUserAndTypeAndMonthAndRecurringExpenseId(u, AlertType.RECURRING_DUE, MONTH, 42L))
                .thenReturn(Optional.empty());

        alertService.getOrGenerate(u, MONTH);

        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, atLeastOnce()).save(cap.capture());
        Alert saved = cap.getAllValues().stream()
                .filter(a -> a.getType() == AlertType.RECURRING_DUE)
                .findFirst().orElseThrow();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.INFO);
        assertThat(saved.getRecurringExpenseId()).isEqualTo(42L);
        assertThat(saved.getMessage()).contains("Netflix");
        assertThat(saved.getMessage()).contains("199.00");
    }

    @Test
    void monthly_dueOutsideWindow_noAlert() {
        User u = user();
        int outsideDay = LocalDate.now().plusDays(5).getDayOfMonth();
        RecurringExpense expense = monthlyExpense(outsideDay);

        stubCommonGetOrGenerate(u);
        when(recurringExpenseRepository.findAllByUser(u)).thenReturn(List.of(expense));

        alertService.getOrGenerate(u, MONTH);

        verify(alertRepository, never()).save(any(Alert.class));
    }

    @Test
    void monthly_alertAlreadyExists_noDuplicate() {
        User u = user();
        int today = LocalDate.now().getDayOfMonth();
        RecurringExpense expense = monthlyExpense(today);

        Alert existing = Alert.builder()
                .id(99L).type(AlertType.RECURRING_DUE).severity(AlertSeverity.INFO)
                .month(MONTH).categoryName("Uncategorized").message("old message")
                .recurringExpenseId(42L)
                .build();

        stubCommonGetOrGenerate(u);
        when(recurringExpenseRepository.findAllByUser(u)).thenReturn(List.of(expense));
        when(alertRepository.findByUserAndTypeAndMonthAndRecurringExpenseId(u, AlertType.RECURRING_DUE, MONTH, 42L))
                .thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);

        alertService.getOrGenerate(u, MONTH);

        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, atLeastOnce()).save(cap.capture());
        long recurringDueSaves = cap.getAllValues().stream()
                .filter(a -> a.getType() == AlertType.RECURRING_DUE)
                .count();
        assertThat(recurringDueSaves).isEqualTo(1);
        assertThat(existing.getMessage()).contains("Netflix");
    }
}
