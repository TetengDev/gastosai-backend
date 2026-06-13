package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.BudgetResponse;
import com.teng.app.gastosai.dto.BudgetSummaryResponse;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.service.BudgetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

	@Mock
	BudgetRepository budgetRepository;

	@Mock
	CategoryRepository categoryRepository;

	@Mock
	ExpenseRepository expenseRepository;

	@InjectMocks
	BudgetService budgetService;

	private User testUser() {
		return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
	}

	private Category testCategory() {
		return Category.builder().id(10L).name("Food").build();
	}

	@Test
	void create_success() {
		User user = testUser();
		Category cat = testCategory();
		BudgetRequest req = new BudgetRequest(10L, "2026-06", new BigDecimal("5000.00"), null, null);

		when(categoryRepository.findById(10L)).thenReturn(Optional.of(cat));
		when(budgetRepository.existsByUserAndCategoryAndMonth(user, cat, "2026-06")).thenReturn(false);
		when(budgetRepository.save(any())).thenAnswer(inv -> {
			Budget b = inv.getArgument(0);
			return Budget.builder()
					.id(1L)
					.user(b.getUser())
					.category(b.getCategory())
					.month(b.getMonth())
					.amountLimit(b.getAmountLimit())
					.currency(b.getCurrency())
					.exchangeRate(b.getExchangeRate())
					.amountLimitInBaseCurrency(b.getAmountLimitInBaseCurrency())
					.build();
		});

		BudgetResponse result = budgetService.create(req, user);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.categoryId()).isEqualTo(10L);
		assertThat(result.categoryName()).isEqualTo("Food");
		assertThat(result.month()).isEqualTo("2026-06");
	}

	@Test
	void create_duplicateBudget_throwsConflict() {
		User user = testUser();
		Category cat = testCategory();
		BudgetRequest req = new BudgetRequest(10L, "2026-06", new BigDecimal("5000.00"), null, null);

		when(categoryRepository.findById(10L)).thenReturn(Optional.of(cat));
		when(budgetRepository.existsByUserAndCategoryAndMonth(user, cat, "2026-06")).thenReturn(true);

		assertThatThrownBy(() -> budgetService.create(req, user))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Budget already exists");
	}

	@Test
	void create_unknownCategory_throwsNotFound() {
		User user = testUser();
		BudgetRequest req = new BudgetRequest(99L, "2026-06", new BigDecimal("5000.00"), null, null);

		when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> budgetService.create(req, user))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Category not found");
	}

	@Test
	void getSummary_onTrack() {
		User user = testUser();
		Category cat = testCategory();
		String month = "2030-12";

		Budget budget = Budget.builder()
				.id(1L).user(user).category(cat).month(month)
				.amountLimit(new BigDecimal("1000.00"))
				.amountLimitInBaseCurrency(new BigDecimal("1000.00"))
				.build();

		ArrayList<Object[]> spent1 = new ArrayList<>();
		spent1.add(new Object[]{10L, new BigDecimal("500.00")});
		when(budgetRepository.findAllByUserAndMonth(user, month)).thenReturn(List.of(budget));
		when(expenseRepository.sumByCategoryAndMonth(user, 2030, 12)).thenReturn(spent1);

		BudgetSummaryResponse summary = budgetService.getSummary(month, user);

		assertThat(summary.items()).hasSize(1);
		assertThat(summary.items().get(0).status()).isEqualTo("ON_TRACK");
		assertThat(summary.items().get(0).percentUsed()).isEqualByComparingTo("50.00");
	}

	@Test
	void getSummary_warning() {
		User user = testUser();
		Category cat = testCategory();
		String month = "2030-12";

		Budget budget = Budget.builder()
				.id(1L).user(user).category(cat).month(month)
				.amountLimit(new BigDecimal("1000.00"))
				.amountLimitInBaseCurrency(new BigDecimal("1000.00"))
				.build();

		ArrayList<Object[]> spent2 = new ArrayList<>();
		spent2.add(new Object[]{10L, new BigDecimal("850.00")});
		when(budgetRepository.findAllByUserAndMonth(user, month)).thenReturn(List.of(budget));
		when(expenseRepository.sumByCategoryAndMonth(user, 2030, 12)).thenReturn(spent2);

		BudgetSummaryResponse summary = budgetService.getSummary(month, user);

		assertThat(summary.items().get(0).status()).isEqualTo("WARNING");
	}

	@Test
	void getSummary_overBudget() {
		User user = testUser();
		Category cat = testCategory();
		String month = "2030-12";

		Budget budget = Budget.builder()
				.id(1L).user(user).category(cat).month(month)
				.amountLimit(new BigDecimal("1000.00"))
				.amountLimitInBaseCurrency(new BigDecimal("1000.00"))
				.build();

		ArrayList<Object[]> spent3 = new ArrayList<>();
		spent3.add(new Object[]{10L, new BigDecimal("1100.00")});
		when(budgetRepository.findAllByUserAndMonth(user, month)).thenReturn(List.of(budget));
		when(expenseRepository.sumByCategoryAndMonth(user, 2030, 12)).thenReturn(spent3);

		BudgetSummaryResponse summary = budgetService.getSummary(month, user);

		assertThat(summary.items().get(0).status()).isEqualTo("OVER_BUDGET");
	}

	@Test
	void getSummary_dailyAllowanceZero_pastMonth() {
		User user = testUser();
		Category cat = testCategory();
		String month = "2020-01";

		Budget budget = Budget.builder()
				.id(1L).user(user).category(cat).month(month)
				.amountLimit(new BigDecimal("1000.00"))
				.amountLimitInBaseCurrency(new BigDecimal("1000.00"))
				.build();

		when(budgetRepository.findAllByUserAndMonth(user, month)).thenReturn(List.of(budget));
		when(expenseRepository.sumByCategoryAndMonth(user, 2020, 1))
				.thenReturn(List.of());

		BudgetSummaryResponse summary = budgetService.getSummary(month, user);

		assertThat(summary.dailyAllowance()).isEqualByComparingTo("0.00");
	}

	@Test
	void delete_notOwned_throwsNotFound() {
		User user = testUser();
		when(budgetRepository.findByIdAndUser(999L, user)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> budgetService.delete(999L, user))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Budget not found");
	}
}
