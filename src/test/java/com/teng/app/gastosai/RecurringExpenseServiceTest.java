package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.dto.UpcomingBillWithBase;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.RecurringExpenseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringExpenseServiceTest {

	@Mock
	RecurringExpenseRepository recurringExpenseRepository;

	@Mock
	CategoryService categoryService;

	@InjectMocks
	RecurringExpenseService recurringExpenseService;

	private User testUser() {
		return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
	}

	private Category testCategory() {
		return Category.builder().id(10L).name("Utilities").build();
	}

	@Test
	void create_returnsCorrectResponse() {
		User user = testUser();
		Category cat = testCategory();
		RecurringExpenseRequest req = new RecurringExpenseRequest(
				"Electric Bill", new BigDecimal("1500.00"), "Utilities",
				Frequency.MONTHLY, 15, null, null, true, null, null
		);

		when(categoryService.getOrCreateByName(eq("Utilities"), any())).thenReturn(cat);
		when(recurringExpenseRepository.save(any())).thenAnswer(inv -> {
			RecurringExpense e = inv.getArgument(0);
			return RecurringExpense.builder()
					.id(1L)
					.user(e.getUser())
					.name(e.getName())
					.amount(e.getAmount())
					.category(e.getCategory())
					.frequency(e.getFrequency())
					.dayOfMonth(e.getDayOfMonth())
					.dayOfWeek(e.getDayOfWeek())
					.active(e.isActive())
					.build();
		});

		RecurringExpenseResponse result = recurringExpenseService.create(req, user);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.name()).isEqualTo("Electric Bill");
		assertThat(result.amount()).isEqualByComparingTo("1500.00");
		assertThat(result.categoryName()).isEqualTo("Utilities");
		assertThat(result.frequency()).isEqualTo(Frequency.MONTHLY);
		assertThat(result.dayOfMonth()).isEqualTo(15);
		assertThat(result.active()).isTrue();
	}

	@Test
	void update_notFound_throwsResourceNotFoundException() {
		User user = testUser();
		RecurringExpenseRequest req = new RecurringExpenseRequest(
				"Water Bill", new BigDecimal("500.00"), "Utilities",
				Frequency.MONTHLY, 10, null, null, true, null, null
		);

		when(recurringExpenseRepository.findByIdAndUser(999L, user)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> recurringExpenseService.update(999L, req, user))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("RecurringExpense not found");
	}

	@Test
	void delete_notFound_throwsResourceNotFoundException() {
		User user = testUser();
		when(recurringExpenseRepository.existsByIdAndUser(999L, user)).thenReturn(false);

		assertThatThrownBy(() -> recurringExpenseService.delete(999L, user))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("RecurringExpense not found");
	}

	/**
	 * The converted amount comes from the stored amount, not from the two-place amount the v1
	 * response displays (TEN-360). A recurring expense may hold four decimals — the column is
	 * NUMERIC(19,4) and the request carries no fraction limit — and converting from the rounded
	 * value would land a centavo or more away from what ExpenseService stores for an expense with
	 * the same amount and rate.
	 */
	@Test
	void findAllWithBase_convertsFromTheStoredAmountNotTheDisplayedOne() {
		User user = testUser();
		RecurringExpense bill = RecurringExpense.builder()
				.id(1L).user(user).name("Cloud Hosting").amount(new BigDecimal("20.0049"))
				.category(testCategory()).frequency(Frequency.MONTHLY).dayOfMonth(5).active(true)
				.currency("USD").exchangeRate(new BigDecimal("58.7500"))
				.build();

		when(recurringExpenseRepository.findAllByUser(user)).thenReturn(List.of(bill));

		RecurringExpenseWithBase result = recurringExpenseService.findAllWithBase(user).get(0);

		// 20.0049 x 58.75 = 1175.287875, to NUMERIC(19,4) scale.
		assertThat(result.amountInBaseCurrency()).isEqualByComparingTo("1175.2879");
		// The displayed amount is still rounded to two places, as v1 has always served it.
		assertThat(result.response().amount()).isEqualByComparingTo("20.00");
	}

	@Test
	void getUpcomingWithBase_convertsFromTheStoredAmountNotTheDisplayedOne() {
		User user = testUser();
		RecurringExpense bill = RecurringExpense.builder()
				.id(1L).user(user).name("Cloud Hosting").amount(new BigDecimal("20.0049"))
				.category(testCategory()).frequency(Frequency.MONTHLY).dayOfMonth(5).active(true)
				.currency("USD").exchangeRate(new BigDecimal("58.7500"))
				.build();

		when(recurringExpenseRepository.findAllByUserAndActiveTrue(user)).thenReturn(List.of(bill));

		List<UpcomingBillWithBase> results = recurringExpenseService.getUpcomingWithBase("2026-06", user);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).amountInBaseCurrency()).isEqualByComparingTo("1175.2879");
	}

	@Test
	void baseAmountOfAPesoBillIsTheAmountItself() {
		User user = testUser();
		RecurringExpense bill = RecurringExpense.builder()
				.id(1L).user(user).name("Rent").amount(new BigDecimal("5000.00"))
				.category(testCategory()).frequency(Frequency.MONTHLY).dayOfMonth(15).active(true)
				.build();

		when(recurringExpenseRepository.findAllByUserAndActiveTrue(user)).thenReturn(List.of(bill));

		UpcomingBillWithBase result = recurringExpenseService.getUpcomingWithBase("2026-06", user).get(0);

		assertThat(result.bill().currency()).isEqualTo("PHP");
		assertThat(result.amountInBaseCurrency()).isEqualByComparingTo(result.bill().amount());
	}

	@Test
	void getUpcoming_monthly_returnsCorrectDueDate() {
		User user = testUser();
		Category cat = testCategory();
		RecurringExpense bill = RecurringExpense.builder()
				.id(1L).user(user).name("Rent").amount(new BigDecimal("5000.00"))
				.category(cat).frequency(Frequency.MONTHLY).dayOfMonth(15).active(true)
				.build();

		when(recurringExpenseRepository.findAllByUserAndActiveTrue(user)).thenReturn(List.of(bill));

		List<UpcomingBillResponse> results = recurringExpenseService.getUpcoming("2026-06", user);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).dueDate()).isEqualTo("2026-06-15");
	}

	@Test
	void getUpcoming_weekly_returnsMultipleOccurrences() {
		User user = testUser();
		Category cat = testCategory();
		RecurringExpense bill = RecurringExpense.builder()
				.id(2L).user(user).name("Weekly Sub").amount(new BigDecimal("100.00"))
				.category(cat).frequency(Frequency.WEEKLY).dayOfWeek(1).active(true)
				.build();

		when(recurringExpenseRepository.findAllByUserAndActiveTrue(user)).thenReturn(List.of(bill));

		List<UpcomingBillResponse> results = recurringExpenseService.getUpcoming("2026-06", user);

		assertThat(results.size()).isGreaterThan(1);
		results.forEach(r -> assertThat(r.name()).isEqualTo("Weekly Sub"));
	}

	@Test
	void getUpcoming_yearly_returnsOneEntry() {
		User user = testUser();
		Category cat = testCategory();
		RecurringExpense bill = RecurringExpense.builder()
				.id(3L).user(user).name("Annual Fee").amount(new BigDecimal("3000.00"))
				.category(cat).frequency(Frequency.YEARLY).dayOfMonth(20).active(true)
				.build();

		when(recurringExpenseRepository.findAllByUserAndActiveTrue(user)).thenReturn(List.of(bill));

		List<UpcomingBillResponse> results = recurringExpenseService.getUpcoming("2026-06", user);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).dueDate()).isEqualTo("2026-06-20");
	}
}
