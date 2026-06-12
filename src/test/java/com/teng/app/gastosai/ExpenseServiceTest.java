package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.MonthlyComparisonResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.ExpenseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    @Mock
    ExpenseRepository expenseRepository;

    @Mock
    CategoryService categoryService;

    @InjectMocks
    ExpenseService expenseService;

    private User regularUser() {
        return User.builder()
                .id(1L)
                .name("Test User")
                .email("user@test.com")
                .password("hash")
                .role(Role.USER)
                .build();
    }

    @Test
    void create_usesUncategorized_whenCategoryBlank() {
        User user = regularUser();
        Category uncategorized = Category.builder().id(1L).name("Uncategorized").build();
        when(categoryService.getOrCreateByName("Uncategorized")).thenReturn(uncategorized);

        Expense saved = Expense.builder()
                .id(10L)
                .amount(new BigDecimal("50.0000"))
                .category(uncategorized)
                .date(LocalDateTime.now())
                .description("Test")
                .build();
        when(expenseRepository.save(any(Expense.class))).thenReturn(saved);

        ExpenseResponse response = expenseService.create(
                new ExpenseRequest(new BigDecimal("50"), "", null, "Test", null, null), user);

        assertThat(response.category()).isEqualTo("Uncategorized");
        verify(categoryService).getOrCreateByName("Uncategorized");
    }

    @Test
    void create_usesProvidedCategory_whenCategorySupplied() {
        User user = regularUser();
        Category mealPlan = Category.builder().id(2L).name("Meal Plan").build();
        when(categoryService.getOrCreateByName("Meal Plan")).thenReturn(mealPlan);

        Expense saved = Expense.builder()
                .id(11L)
                .amount(new BigDecimal("120.0000"))
                .category(mealPlan)
                .date(LocalDateTime.now())
                .description("Lunch")
                .build();
        when(expenseRepository.save(any(Expense.class))).thenReturn(saved);

        ExpenseResponse response = expenseService.create(
                new ExpenseRequest(new BigDecimal("120"), "Meal Plan", null, "Lunch", null, null), user);

        assertThat(response.category()).isEqualTo("Meal Plan");
        verify(categoryService).getOrCreateByName("Meal Plan");
    }

    @Test
    void findAll_returnsSortedList_forNonAdminUser() {
        User user = regularUser();
        Category cat = Category.builder().id(1L).name("Extras").build();

        Expense e1 = Expense.builder().id(1L).amount(new BigDecimal("100.0000"))
                .category(cat).date(LocalDateTime.now()).description("A").build();
        Expense e2 = Expense.builder().id(2L).amount(new BigDecimal("200.0000"))
                .category(cat).date(LocalDateTime.now().minusDays(1)).description("B").build();

        when(expenseRepository.findAllByUserOrderByDateDesc(user)).thenReturn(List.of(e1, e2));

        List<ExpenseResponse> result = expenseService.findAll(user);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(1).id()).isEqualTo(2L);
    }

    @Test
    void findAll_usesAdminQuery_forAdminUser() {
        User admin = User.builder()
                .id(2L)
                .name("Admin")
                .email("admin@test.com")
                .password("hash")
                .role(Role.ADMIN)
                .build();
        Category cat = Category.builder().id(1L).name("Extras").build();

        Expense e = Expense.builder().id(5L).amount(new BigDecimal("50.0000"))
                .category(cat).date(LocalDateTime.now()).description("Admin expense").build();

        when(expenseRepository.findAll(any(Sort.class))).thenReturn(List.of(e));

        List<ExpenseResponse> result = expenseService.findAll(admin);

        assertThat(result).hasSize(1);
    }

    @Test
    void update_updatesAndReturnsResponse() {
        User user = regularUser();
        Category oldCat = Category.builder().id(1L).name("Extras").build();
        Category newCat = Category.builder().id(2L).name("Meal Plan").build();

        Expense existing = Expense.builder()
                .id(7L)
                .amount(new BigDecimal("80.0000"))
                .category(oldCat)
                .date(LocalDateTime.now())
                .description("Old desc")
                .build();

        when(expenseRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(existing));
        when(categoryService.getOrCreateByName("Meal Plan")).thenReturn(newCat);
        when(expenseRepository.save(any(Expense.class))).thenReturn(existing);

        ExpenseResponse response = expenseService.update(7L,
                new ExpenseRequest(new BigDecimal("90"), "Meal Plan", null, "New desc", null, null), user);

        assertThat(existing.getDescription()).isEqualTo("New desc");
        assertThat(existing.getCategory().getName()).isEqualTo("Meal Plan");
        verify(expenseRepository).save(existing);
    }

    @Test
    void delete_callsRepositoryDeleteById_forNonAdmin() {
        User user = regularUser();
        when(expenseRepository.existsByIdAndUser(5L, user)).thenReturn(true);

        expenseService.delete(5L, user);

        verify(expenseRepository).deleteById(5L);
    }

    @Test
    void delete_callsRepositoryDeleteById_forAdmin() {
        User admin = User.builder()
                .id(2L)
                .name("Admin")
                .email("admin@test.com")
                .password("hash")
                .role(Role.ADMIN)
                .build();
        when(expenseRepository.existsById(5L)).thenReturn(true);

        expenseService.delete(5L, admin);

        verify(expenseRepository).deleteById(5L);
    }

    @Test
    void findAll_filtersBy_fromAndTo() {
        User user = regularUser();
        Category cat = Category.builder().id(1L).name("Food").build();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        Expense e = Expense.builder().id(1L).amount(new BigDecimal("100.0000"))
                .category(cat).date(LocalDateTime.of(2026, 1, 15, 12, 0)).description("A").build();

        when(expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                user, from.atStartOfDay(), to.plusDays(1).atStartOfDay())).thenReturn(List.of(e));

        List<ExpenseResponse> result = expenseService.findAll(user, from, to);
        assertThat(result).hasSize(1);
    }

    @Test
    void findAll_filtersBy_fromOnly() {
        User user = regularUser();
        Category cat = Category.builder().id(1L).name("Food").build();
        LocalDate from = LocalDate.of(2026, 6, 1);
        Expense e = Expense.builder().id(2L).amount(new BigDecimal("50.0000"))
                .category(cat).date(LocalDateTime.of(2026, 6, 10, 10, 0)).description("B").build();

        when(expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(
                user, from.atStartOfDay())).thenReturn(List.of(e));

        List<ExpenseResponse> result = expenseService.findAll(user, from, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void findAll_filtersBy_toOnly() {
        User user = regularUser();
        Category cat = Category.builder().id(1L).name("Food").build();
        LocalDate to = LocalDate.of(2026, 3, 31);
        Expense e = Expense.builder().id(3L).amount(new BigDecimal("75.0000"))
                .category(cat).date(LocalDateTime.of(2026, 3, 20, 9, 0)).description("C").build();

        when(expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(
                user, to.plusDays(1).atStartOfDay())).thenReturn(List.of(e));

        List<ExpenseResponse> result = expenseService.findAll(user, null, to);
        assertThat(result).hasSize(1);
    }

    @Test
    void findAll_returnsAll_whenNoParams() {
        User user = regularUser();
        Category cat = Category.builder().id(1L).name("Food").build();
        Expense e = Expense.builder().id(4L).amount(new BigDecimal("200.0000"))
                .category(cat).date(LocalDateTime.now()).description("D").build();

        when(expenseRepository.findAllByUserOrderByDateDesc(user)).thenReturn(List.of(e));

        List<ExpenseResponse> result = expenseService.findAll(user, null, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void monthlyComparison_computesChangePercent() {
        User user = regularUser();
        when(expenseRepository.sumForMonth(user, 2026, 6)).thenReturn(new BigDecimal("1200.00"));
        when(expenseRepository.sumForMonth(user, 2026, 5)).thenReturn(new BigDecimal("1000.00"));

        MonthlyComparisonResponse result = expenseService.monthlyComparison(user, "2026-06");

        assertThat(result.currentTotal()).isEqualByComparingTo("1200.00");
        assertThat(result.previousTotal()).isEqualByComparingTo("1000.00");
        assertThat(result.changePercent()).isEqualByComparingTo("20.00");
    }

    @Test
    void monthlyComparison_returnsNullChangePercent_whenPreviousIsZero() {
        User user = regularUser();
        when(expenseRepository.sumForMonth(user, 2026, 6)).thenReturn(new BigDecimal("500.00"));
        when(expenseRepository.sumForMonth(user, 2026, 5)).thenReturn(BigDecimal.ZERO);

        MonthlyComparisonResponse result = expenseService.monthlyComparison(user, "2026-06");

        assertThat(result.changePercent()).isNull();
    }

    @Test
    void monthlyComparison_handlesJanuaryToPreviousDecember() {
        User user = regularUser();
        when(expenseRepository.sumForMonth(user, 2026, 1)).thenReturn(new BigDecimal("300.00"));
        when(expenseRepository.sumForMonth(user, 2025, 12)).thenReturn(new BigDecimal("400.00"));

        MonthlyComparisonResponse result = expenseService.monthlyComparison(user, "2026-01");

        assertThat(result.previousTotal()).isEqualByComparingTo("400.00");
        assertThat(result.changePercent()).isEqualByComparingTo("-25.00");
    }

    @Test
    void create_setsDateToNow_whenDateNotProvided() {
        User user = regularUser();
        Category uncategorized = Category.builder().id(1L).name("Uncategorized").build();
        when(categoryService.getOrCreateByName("Uncategorized")).thenReturn(uncategorized);

        when(expenseRepository.save(argThat(e -> e.getDate() != null)))
                .thenAnswer(inv -> {
                    Expense e = inv.getArgument(0);
                    e = Expense.builder()
                            .id(20L)
                            .amount(e.getAmount())
                            .category(e.getCategory())
                            .date(e.getDate())
                            .description(e.getDescription())
                            .build();
                    return e;
                });

        ExpenseResponse response = expenseService.create(
                new ExpenseRequest(new BigDecimal("30"), null, null, "No date", null, null), user);

        assertThat(response.date()).isNotNull();
    }
}
