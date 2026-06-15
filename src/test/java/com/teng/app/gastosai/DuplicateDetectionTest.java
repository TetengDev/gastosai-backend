package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.RecurringExpenseService;
import com.teng.app.gastosai.service.SavingsGoalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicateDetectionTest {

    @Mock SavingsGoalRepository goalRepository;
    @Mock RecurringExpenseRepository recurringRepository;
    @Mock CategoryService categoryService;

    private final User user = User.builder().id(1L).email("u@test.com").name("U").password("x").build();

    // --- Goals (duplicate by name) ---

    @Test
    void goal_duplicateName_conflictWhenNotForced() {
        when(goalRepository.existsByUserAndNameIgnoreCase(user, "Emergency Fund")).thenReturn(true);
        SavingsGoalService service = new SavingsGoalService(goalRepository);
        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("1000"), BigDecimal.ZERO, null, false, "PHP");

        assertThatThrownBy(() -> service.create(req, user, false)).isInstanceOf(ResponseStatusException.class);
        verify(goalRepository, never()).save(any());
    }

    @Test
    void goal_duplicateName_savesWhenForced() {
        SavingsGoalService service = new SavingsGoalService(goalRepository);
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("1000"), BigDecimal.ZERO, null, false, "PHP");

        assertThatCode(() -> service.create(req, user, true)).doesNotThrowAnyException();
        verify(goalRepository).save(any(SavingsGoal.class));
        verify(goalRepository, never()).existsByUserAndNameIgnoreCase(any(), any());
    }

    // --- Recurring (duplicate by name + frequency) ---

    @Test
    void recurring_duplicateNameAndFrequency_conflictWhenNotForced() {
        when(recurringRepository.existsByUserAndNameIgnoreCaseAndFrequency(user, "Rent", Frequency.MONTHLY)).thenReturn(true);
        RecurringExpenseService service = new RecurringExpenseService(recurringRepository, categoryService);
        RecurringExpenseRequest req = new RecurringExpenseRequest("Rent", new BigDecimal("5000"), "Rent",
                Frequency.MONTHLY, 1, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(req, user, false)).isInstanceOf(ResponseStatusException.class);
        verify(recurringRepository, never()).save(any());
    }

    @Test
    void recurring_duplicateNameAndFrequency_savesWhenForced() {
        RecurringExpenseService service = new RecurringExpenseService(recurringRepository, categoryService);
        when(categoryService.getOrCreateByName(eq("Rent"))).thenReturn(Category.builder().id(2L).name("Rent").build());
        when(recurringRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RecurringExpenseRequest req = new RecurringExpenseRequest("Rent", new BigDecimal("5000"), "Rent",
                Frequency.MONTHLY, 1, null, null, null, null, null);

        assertThatCode(() -> service.create(req, user, true)).doesNotThrowAnyException();
        verify(recurringRepository).save(any(RecurringExpense.class));
        verify(recurringRepository, never()).existsByUserAndNameIgnoreCaseAndFrequency(any(), any(), any());
    }
}
