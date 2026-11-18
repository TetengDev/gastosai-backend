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

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	// --- Paid state (TEN-503) ---

	/** 16:30 UTC on 30 September is 00:30 on 1 October in Manila. */
	private static final Instant MANILA_OCTOBER_FIRST = Instant.parse("2026-09-30T16:30:00Z");

	private void pinClock(Instant instant) {
		ReflectionTestUtils.setField(recurringExpenseService, "clock", Clock.fixed(instant, ZoneOffset.UTC));
	}

	/** A row of the single list query: one bill's paid state, as the repository projects it. */
	private static RecurringExpenseRepository.PaymentSummary summary(long id, boolean paidThisMonth,
			String lastPaidMonth) {
		return new RecurringExpenseRepository.PaymentSummary() {
			public Long getId() { return id; }
			public Boolean getPaidThisMonth() { return paidThisMonth; }
			public String getLastPaidMonth() { return lastPaidMonth; }
		};
	}

	private RecurringExpense bill(long id, User user) {
		return RecurringExpense.builder()
				.id(id).user(user).name("Bill " + id).amount(new BigDecimal("100.00"))
				.category(testCategory()).frequency(Frequency.MONTHLY).dayOfMonth(5).active(true)
				.build();
	}

	/**
	 * The current occurrence is decided in Asia/Manila, not in UTC or the server's zone: at
	 * 2026-09-30T16:30Z a UTC reading would still say September, and a bill paid for October would
	 * show as unpaid for the first eight hours of the Manila month.
	 */
	@Test
	void findAll_paidFlagUsesTheManilaMonth_atTheUtcMonthBoundary() {
		User user = testUser();
		pinClock(MANILA_OCTOBER_FIRST);
		when(recurringExpenseRepository.findAllByUser(user)).thenReturn(List.of(bill(1L, user), bill(2L, user)));
		when(recurringExpenseRepository.findPaymentSummaries(1L, "2026-10"))
				.thenReturn(List.of(summary(1L, true, "2026-10")));

		List<RecurringExpenseResponse> result = recurringExpenseService.findAll(user);

		assertThat(result).extracting(RecurringExpenseResponse::id, RecurringExpenseResponse::paidThisMonth)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(1L, true),
						org.assertj.core.groups.Tuple.tuple(2L, false));
	}

	/**
	 * A bill left unpaid past the end of its month must not vanish on the 1st: lastPaidMonth lets a
	 * client see that September was paid and October was not. A bill never paid serves null.
	 */
	@Test
	void findAll_servesLastPaidMonth_andNullForANeverPaidBill() {
		User user = testUser();
		pinClock(MANILA_OCTOBER_FIRST);
		when(recurringExpenseRepository.findAllByUser(user)).thenReturn(List.of(bill(1L, user), bill(2L, user)));
		when(recurringExpenseRepository.findPaymentSummaries(1L, "2026-10"))
				.thenReturn(List.of(summary(1L, false, "2026-09")));

		List<RecurringExpenseResponse> result = recurringExpenseService.findAll(user);

		assertThat(result).extracting(RecurringExpenseResponse::id, RecurringExpenseResponse::paidThisMonth,
						RecurringExpenseResponse::lastPaidMonth)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(1L, false, "2026-09"),
						org.assertj.core.groups.Tuple.tuple(2L, false, null));
	}

	@Test
	void update_servesTheStoredPaidFlag_notADefaultFalse() {
		User user = testUser();
		pinClock(MANILA_OCTOBER_FIRST);
		RecurringExpense existing = bill(7L, user);
		when(recurringExpenseRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(existing));
		when(categoryService.getOrCreateByName(eq("Utilities"), any())).thenReturn(testCategory());
		when(recurringExpenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(recurringExpenseRepository.findPaymentSummaries(1L, "2026-10"))
				.thenReturn(List.of(summary(7L, true, "2026-10")));

		RecurringExpenseResponse result = recurringExpenseService.update(7L, new RecurringExpenseRequest(
				"Bill 7", new BigDecimal("120.00"), "Utilities", Frequency.MONTHLY, 5, null, null, true, null, null),
				user);

		assertThat(result.paidThisMonth()).isTrue();
		assertThat(result.lastPaidMonth()).isEqualTo("2026-10");
	}

	@Test
	void markPaid_recordsTheMonthWithAUtcTimestamp() {
		User user = testUser();
		pinClock(MANILA_OCTOBER_FIRST);
		when(recurringExpenseRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(bill(7L, user)));

		recurringExpenseService.markPaid(7L, "2026-09", user);

		verify(recurringExpenseRepository).insertPaymentIfAbsent(7L, "2026-09",
				LocalDateTime.of(2026, 9, 30, 16, 30));
	}

	@Test
	void markPaid_anotherUsersBill_isNotFound_andWritesNothing() {
		User user = testUser();
		when(recurringExpenseRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> recurringExpenseService.markPaid(99L, "2026-09", user))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(recurringExpenseRepository, never()).insertPaymentIfAbsent(anyLong(), anyString(), any());
	}

	@Test
	void unmarkPaid_deletesTheMonth() {
		User user = testUser();
		when(recurringExpenseRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(bill(7L, user)));

		recurringExpenseService.unmarkPaid(7L, "2026-09", user);

		verify(recurringExpenseRepository).deletePayment(7L, "2026-09");
	}

	@Test
	void unmarkPaid_anotherUsersBill_isNotFound_andDeletesNothing() {
		User user = testUser();
		when(recurringExpenseRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> recurringExpenseService.unmarkPaid(99L, "2026-09", user))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(recurringExpenseRepository, never()).deletePayment(anyLong(), anyString());
	}

	/** {@code 2026-13} passes a {@code \d{4}-\d{2}} shape check; it must still be a 400, not a 500. */
	@Test
	void markPaid_malformedMonth_isRejectedBeforeAnyLookup() {
		User user = testUser();
		for (String month : List.of("bad", "2026-13", "2026-1", "2026-09-01", "+2026-09")) {
			assertThatThrownBy(() -> recurringExpenseService.markPaid(7L, month, user))
					.as(month)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("YYYY-MM");
		}
		verify(recurringExpenseRepository, never()).findByIdAndUser(anyLong(), any());
	}
}
