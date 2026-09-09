package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.dto.v2.Money;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.ProjectRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.ExpenseService;
import com.teng.app.gastosai.service.RecurringExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two paths that convert an amount to the base currency — the value {@link ExpenseService}
 * stores on an expense, and the value {@link RecurringExpenseService} attaches to the v2 recurring
 * and upcoming-bill responses — agree to the centavo for the same amount and rate.
 *
 * <p>They agree because both call {@link Money#toBaseCurrency}. This test is what enforces that:
 * before it, only a comment beside each copy of the expression did.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseCurrencyConversionParityTest {

	@Mock
	ExpenseRepository expenseRepository;

	@Mock
	RecurringExpenseRepository recurringExpenseRepository;

	@Mock
	CategoryService categoryService;

	@Mock
	ProjectRepository projectRepository;

	@InjectMocks
	ExpenseService expenseService;

	@InjectMocks
	RecurringExpenseService recurringExpenseService;

	private final User user = User.builder()
			.id(1L)
			.name("Test User")
			.email("user@test.com")
			.password("hash")
			.role(Role.USER)
			.build();

	private final Category category = Category.builder().id(5L).name("Uncategorized").build();

	@ParameterizedTest(name = "amount {0} at rate {1} converts to {2} on both paths")
	@CsvSource({
			// A four-decimal amount: the shape TEN-360 got wrong by converting a rounded input.
			"1234.5678, 0.017543, 21.6580",
			"100.00, 57.250000, 5725.0000",
			// A tie at the fifth decimal, to pin the rounding mode as well as the scale.
			"1.00005, 1.000000, 1.0001"
	})
	void expenseAndRecurringConvertIdentically(String amount, String rate, String expected) {
		BigDecimal onExpense = expenseBase(new BigDecimal(amount), new BigDecimal(rate));
		BigDecimal onRecurring = recurringBase(new BigDecimal(amount), new BigDecimal(rate));

		assertThat(onExpense).isEqualTo(new BigDecimal(expected)).hasScaleOf(4);
		assertThat(onRecurring).isEqualTo(onExpense);
	}

	@Test
	void anAbsentRateConvertsIdenticallyOnBothPaths() {
		BigDecimal amount = new BigDecimal("1234.5678");
		BigDecimal onExpense = expenseBase(amount, null);
		BigDecimal onRecurring = recurringBase(amount, null);

		assertThat(onExpense).isEqualTo(new BigDecimal("1234.5678"));
		assertThat(onRecurring).isEqualTo(onExpense);
	}

	@BeforeEach
	void stubRepositories() {
		when(categoryService.resolveByMerchant(any(), any())).thenReturn(Optional.empty());
		when(categoryService.getOrCreateByName(any(), any())).thenReturn(category);
		doAnswer(inv -> inv.getArgument(0)).when(expenseRepository).save(any());
		doAnswer(inv -> {
			RecurringExpense e = inv.getArgument(0);
			e.setId(1L);
			return e;
		}).when(recurringExpenseRepository).save(any());
	}

	/** What {@link ExpenseService} stores in {@code amount_in_base_currency}. */
	private BigDecimal expenseBase(BigDecimal amount, BigDecimal rate) {
		ExpenseRequest request = new ExpenseRequest(
				amount, null, null, "Electric bill", null, null, "USD", rate);
		expenseService.create(request, user, ExpenseSource.MANUAL);

		ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
		verify(expenseRepository, atLeastOnce()).save(saved.capture());
		return saved.getValue().getAmountInBaseCurrency();
	}

	/** What {@link RecurringExpenseService} attaches to a v2 recurring response. */
	private BigDecimal recurringBase(BigDecimal amount, BigDecimal rate) {
		RecurringExpenseRequest request = new RecurringExpenseRequest(
				"Electric bill", amount, null, Frequency.MONTHLY, 15, null, null, true, "USD", rate);
		RecurringExpenseWithBase result = recurringExpenseService.createWithBase(request, user, true);
		return result.amountInBaseCurrency();
	}
}
