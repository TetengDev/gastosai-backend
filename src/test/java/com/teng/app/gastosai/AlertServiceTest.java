package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.*;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock AlertRepository alertRepository;
    @Mock BudgetRepository budgetRepository;
    @Mock ExpenseRepository expenseRepository;
    @Mock RecurringExpenseRepository recurringExpenseRepository;

    @InjectMocks AlertService alertService;

    private User user() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
    }

    private Category category(Long id, String name) {
        return Category.builder().id(id).name(name).build();
    }

    private Budget budget(User u, Category c, String month, BigDecimal limit) {
        return Budget.builder()
                .id(1L).user(u).category(c).month(month)
                .amountLimit(limit)
                .amountLimitInBaseCurrency(limit)
                .build();
    }

    private Alert alert(AlertType type, AlertSeverity severity) {
        return Alert.builder()
                .id(10L).type(type).severity(severity)
                .month("2026-06").categoryName("Food").message("msg")
                .build();
    }

    // --- budget alert: exceeded ---

    @Test
    void getOrGenerate_budgetExceeded_savesAlert() {
        User u = user();
        Category cat = category(10L, "Food");
        Budget b = budget(u, cat, "2026-06", new BigDecimal("1000.00"));

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of(b));
        ArrayList<Object[]> spent1 = new ArrayList<>();
        spent1.add(new Object[]{10L, new BigDecimal("1100.00")});
        when(expenseRepository.sumByCategoryAndMonth(u, 2026, 6)).thenReturn(spent1);
        when(alertRepository.findByUserAndTypeAndMonthAndCategoryName(u, AlertType.BUDGET_EXCEEDED, "2026-06", "Food"))
                .thenReturn(Optional.empty());
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(null);
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, atLeastOnce()).save(cap.capture());
        Alert saved = cap.getAllValues().stream()
                .filter(a -> a.getType() == AlertType.BUDGET_EXCEEDED)
                .findFirst().orElseThrow();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(saved.getCategoryName()).isEqualTo("Food");
        assertThat(saved.getMessage()).contains("Budget exceeded");
    }

    // --- budget alert: warning (80–99%) ---

    @Test
    void getOrGenerate_budgetWarning_savesWarningAlert() {
        User u = user();
        Category cat = category(10L, "Food");
        Budget b = budget(u, cat, "2026-06", new BigDecimal("1000.00"));

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of(b));
        ArrayList<Object[]> spent2 = new ArrayList<>();
        spent2.add(new Object[]{10L, new BigDecimal("850.00")});
        when(expenseRepository.sumByCategoryAndMonth(u, 2026, 6)).thenReturn(spent2);
        when(alertRepository.findByUserAndTypeAndMonthAndCategoryName(u, AlertType.BUDGET_WARNING, "2026-06", "Food"))
                .thenReturn(Optional.empty());
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(null);
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, atLeastOnce()).save(cap.capture());
        Alert saved = cap.getAllValues().stream()
                .filter(a -> a.getType() == AlertType.BUDGET_WARNING)
                .findFirst().orElseThrow();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(saved.getMessage()).contains("Approaching budget limit");
    }

    // --- no alert when under 80% ---

    @Test
    void getOrGenerate_underThreshold_noAlertSaved() {
        User u = user();
        Category cat = category(10L, "Food");
        Budget b = budget(u, cat, "2026-06", new BigDecimal("1000.00"));

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of(b));
        ArrayList<Object[]> spent3 = new ArrayList<>();
        spent3.add(new Object[]{10L, new BigDecimal("500.00")});
        when(expenseRepository.sumByCategoryAndMonth(u, 2026, 6)).thenReturn(spent3);
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(null);
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        verify(alertRepository, never()).save(any());
    }

    // --- spending spike ---

    @Test
    void getOrGenerate_spendingSpike_savesAlert() {
        User u = user();

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of());
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(new BigDecimal("1600.00"));
        when(expenseRepository.sumForMonth(u, 2026, 5)).thenReturn(new BigDecimal("1000.00"));
        when(alertRepository.findByUserAndTypeAndMonthAndCategoryName(u, AlertType.SPENDING_SPIKE, "2026-06", ""))
                .thenReturn(Optional.empty());
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, atLeastOnce()).save(cap.capture());
        Alert saved = cap.getAllValues().stream()
                .filter(a -> a.getType() == AlertType.SPENDING_SPIKE)
                .findFirst().orElseThrow();
        assertThat(saved.getMessage()).contains("Spending spike");
    }

    // --- no spike when under 1.5x threshold ---

    @Test
    void getOrGenerate_noSpike_belowThreshold() {
        User u = user();

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of());
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(new BigDecimal("1400.00"));
        when(expenseRepository.sumForMonth(u, 2026, 5)).thenReturn(new BigDecimal("1000.00"));
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        verify(alertRepository, never()).save(any());
    }

    // --- no spike when no previous month data ---

    @Test
    void getOrGenerate_noPrevMonthData_noSpikeAlert() {
        User u = user();

        when(budgetRepository.findAllByUserAndMonth(u, "2026-06")).thenReturn(List.of());
        when(expenseRepository.sumForMonth(u, 2026, 6)).thenReturn(new BigDecimal("2000.00"));
        when(expenseRepository.sumForMonth(u, 2026, 5)).thenReturn(null);
        when(alertRepository.findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(u, "2026-06"))
                .thenReturn(List.of());

        alertService.getOrGenerate(u, "2026-06");

        verify(alertRepository, never()).save(any());
    }

    // --- markRead ---

    @Test
    void markRead_setsReadTrue() {
        User u = user();
        Alert a = alert(AlertType.BUDGET_WARNING, AlertSeverity.WARNING);
        a.setUser(u);
        when(alertRepository.findByIdAndUser(10L, u)).thenReturn(Optional.of(a));
        when(alertRepository.save(a)).thenReturn(a);

        alertService.markRead(10L, u);

        assertThat(a.isRead()).isTrue();
        verify(alertRepository).save(a);
    }

    // --- dismiss ---

    @Test
    void dismiss_setsDismissedTrue() {
        User u = user();
        Alert a = alert(AlertType.BUDGET_WARNING, AlertSeverity.WARNING);
        a.setUser(u);
        when(alertRepository.findByIdAndUser(10L, u)).thenReturn(Optional.of(a));
        when(alertRepository.save(a)).thenReturn(a);

        alertService.dismiss(10L, u);

        assertThat(a.isDismissed()).isTrue();
    }

    // --- not found ---

    @Test
    void markRead_notFound_throws() {
        User u = user();
        when(alertRepository.findByIdAndUser(99L, u)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertService.markRead(99L, u))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Alert not found");
    }

    @Test
    void delete_notFound_throws() {
        User u = user();
        when(alertRepository.findByIdAndUser(99L, u)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertService.delete(99L, u))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
