package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.JacksonTimeConfig;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.dto.UpcomingBillWithBase;
import com.teng.app.gastosai.dto.v2.Money;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecurringExpenseService {

	private final RecurringExpenseRepository recurringExpenseRepository;
	private final CategoryService categoryService;

	/**
	 * Where "now" comes from for the paid state. Not a constructor dependency: there is no
	 * {@code Clock} bean, and the zone is applied when the clock is read, so "this month" is the
	 * Manila month whatever the server's zone is (same seam as {@code ExpenseService}).
	 */
	private Clock clock = Clock.systemUTC();

	/** No duplicate check (chat path — explicit user intent). */
	@Transactional
	public RecurringExpenseResponse create(RecurringExpenseRequest req, User user) {
		return create(req, user, true);
	}

	@Transactional
	public RecurringExpenseResponse create(RecurringExpenseRequest req, User user, boolean force) {
		return createWithBase(req, user, force).response();
	}

	/** {@link #create(RecurringExpenseRequest, User, boolean)} with the converted amount attached. */
	@Transactional
	public RecurringExpenseWithBase createWithBase(RecurringExpenseRequest req, User user, boolean force) {
		if (!force && recurringExpenseRepository.existsByUserAndNameIgnoreCaseAndFrequency(user, req.name(), req.frequency())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A " + req.frequency().name().toLowerCase() + " recurring expense named \"" + req.name() + "\" already exists.");
		}
		String categoryName = (req.categoryName() != null && !req.categoryName().isBlank())
				? req.categoryName()
				: "Uncategorized";
		Category category = categoryService.getOrCreateByName(categoryName, user);

		RecurringExpense saved = recurringExpenseRepository.save(RecurringExpense.builder()
				.user(user)
				.name(req.name())
				.amount(req.amount())
				.category(category)
				.frequency(req.frequency())
				.dayOfMonth(req.dayOfMonth())
				.dayOfWeek(req.dayOfWeek())
				.monthOfYear(req.monthOfYear())
				.active(req.active() == null || req.active())
				.currency(req.currency() != null ? req.currency() : "PHP")
				.exchangeRate(req.exchangeRate() != null ? req.exchangeRate() : BigDecimal.ONE)
				.build());

		return toResponse(saved, false);
	}

	@Transactional(readOnly = true)
	public List<RecurringExpenseResponse> findAll(User user) {
		return findAllWithBase(user).stream()
				.map(RecurringExpenseWithBase::response)
				.toList();
	}

	/** {@link #findAll(User)} with each row's converted amount attached. */
	@Transactional(readOnly = true)
	public List<RecurringExpenseWithBase> findAllWithBase(User user) {
		Set<Long> paid = paidThisMonth(user);
		return recurringExpenseRepository.findAllByUser(user).stream()
				.map(e -> toResponse(e, paid.contains(e.getId())))
				.toList();
	}

	@Transactional
	public RecurringExpenseResponse update(Long id, RecurringExpenseRequest req, User user) {
		return updateWithBase(id, req, user).response();
	}

	/** {@link #update(Long, RecurringExpenseRequest, User)} with the converted amount attached. */
	@Transactional
	public RecurringExpenseWithBase updateWithBase(Long id, RecurringExpenseRequest req, User user) {
		RecurringExpense expense = recurringExpenseRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("RecurringExpense not found: " + id));

		String categoryName = (req.categoryName() != null && !req.categoryName().isBlank())
				? req.categoryName()
				: "Uncategorized";
		Category category = categoryService.getOrCreateByName(categoryName, user);

		expense.setName(req.name());
		expense.setAmount(req.amount());
		expense.setCategory(category);
		expense.setFrequency(req.frequency());
		expense.setDayOfMonth(req.dayOfMonth());
		expense.setDayOfWeek(req.dayOfWeek());
		expense.setMonthOfYear(req.monthOfYear());
		if (req.active() != null) {
			expense.setActive(req.active());
		}
		expense.setCurrency(req.currency() != null ? req.currency() : "PHP");
		expense.setExchangeRate(req.exchangeRate() != null ? req.exchangeRate() : BigDecimal.ONE);

		RecurringExpense saved = recurringExpenseRepository.save(expense);
		return toResponse(saved, paidThisMonth(user).contains(saved.getId()));
	}

	@Transactional
	public void delete(Long id, User user) {
		if (!recurringExpenseRepository.existsByIdAndUser(id, user)) {
			throw new ResourceNotFoundException("RecurringExpense not found: " + id);
		}
		recurringExpenseRepository.deleteById(id);
	}

	@Transactional
	public void deleteAll(User user) {
		recurringExpenseRepository.deleteAll(recurringExpenseRepository.findAllByUser(user));
	}

	/**
	 * Marks the bill paid for {@code month} ({@code YYYY-MM}). Idempotent: an already-paid month is
	 * left as it is, by the database's unique constraint rather than a check-then-insert.
	 */
	@Transactional
	public void markPaid(Long id, String month, User user) {
		String validMonth = parseMonth(month);
		RecurringExpense bill = findOwned(id, user);
		recurringExpenseRepository.insertPaymentIfAbsent(bill.getId(), validMonth,
				LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
	}

	/** Undoes {@link #markPaid}. Idempotent: undoing an unpaid month succeeds and changes nothing. */
	@Transactional
	public void unmarkPaid(Long id, String month, User user) {
		String validMonth = parseMonth(month);
		RecurringExpense bill = findOwned(id, user);
		recurringExpenseRepository.deletePayment(bill.getId(), validMonth);
	}

	/** Another user's bill is a 404, the same as a missing one — its existence is not disclosed. */
	private RecurringExpense findOwned(Long id, User user) {
		return recurringExpenseRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("RecurringExpense not found: " + id));
	}

	/**
	 * A strict {@code YYYY-MM}. The shape check alone would let {@code 2026-13} through to a
	 * {@link DateTimeParseException}, which no handler maps and so would surface as a 500.
	 */
	private static String parseMonth(String month) {
		if (month == null || !month.matches("\\d{4}-\\d{2}")) {
			throw new IllegalArgumentException("Invalid month format. Expected YYYY-MM.");
		}
		try {
			return YearMonth.parse(month).toString();
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("Invalid month format. Expected YYYY-MM.");
		}
	}

	/** Ids of the user's bills paid for the current Manila month, in one query for the whole list. */
	private Set<Long> paidThisMonth(User user) {
		String month = YearMonth.now(clock.withZone(JacksonTimeConfig.APP_ZONE)).toString();
		return new HashSet<>(recurringExpenseRepository.findPaidRecurringExpenseIds(user.getId(), month));
	}

	@Transactional(readOnly = true)
	public List<UpcomingBillResponse> getUpcoming(String month, User user) {
		return getUpcomingWithBase(month, user).stream()
				.map(UpcomingBillWithBase::bill)
				.toList();
	}

	/**
	 * The same bills, each paired with its amount converted to the base currency.
	 *
	 * <p>{@code /api/v2} serves that converted amount and the v1 response carries neither it nor
	 * the rate to derive one. Computing it here, from the entity, keeps the conversion server-side
	 * and at the stored precision without changing the v1 shape.
	 */
	@Transactional(readOnly = true)
	public List<UpcomingBillWithBase> getUpcomingWithBase(String month, User user) {
		YearMonth yearMonth = YearMonth.parse(month);
		int year = yearMonth.getYear();
		int monthValue = yearMonth.getMonthValue();

		List<RecurringExpense> bills = recurringExpenseRepository.findAllByUserAndActiveTrue(user);
		List<UpcomingBillWithBase> results = new ArrayList<>();

		for (RecurringExpense bill : bills) {
			if (bill.getFrequency() == Frequency.MONTHLY) {
				int dom = bill.getDayOfMonth() != null ? bill.getDayOfMonth() : 1;
				LocalDate due = LocalDate.of(year, monthValue, Math.min(dom, yearMonth.lengthOfMonth()));
				results.add(toUpcomingResponse(bill, due));
			} else if (bill.getFrequency() == Frequency.WEEKLY) {
				int targetDow = bill.getDayOfWeek() != null ? bill.getDayOfWeek() : 1;
				LocalDate start = yearMonth.atDay(1);
				LocalDate end = yearMonth.atEndOfMonth();
				LocalDate cursor = start;
				while (!cursor.isAfter(end)) {
					if (cursor.getDayOfWeek().getValue() == targetDow) {
						results.add(toUpcomingResponse(bill, cursor));
					}
					cursor = cursor.plusDays(1);
				}
			} else if (bill.getFrequency() == Frequency.YEARLY) {
				if (bill.getMonthOfYear() != null && bill.getMonthOfYear() != monthValue) continue;
				int dom = bill.getDayOfMonth() != null ? bill.getDayOfMonth() : 1;
				LocalDate due = LocalDate.of(year, monthValue, Math.min(dom, yearMonth.lengthOfMonth()));
				results.add(toUpcomingResponse(bill, due));
			}
		}

		results.sort(Comparator.comparing(result -> result.bill().dueDate()));
		return results;
	}

	private UpcomingBillWithBase toUpcomingResponse(RecurringExpense bill, LocalDate due) {
		UpcomingBillResponse response = new UpcomingBillResponse(
				bill.getId(),
				bill.getName(),
				bill.getAmount().setScale(2, RoundingMode.HALF_UP),
				bill.getCategory() != null ? bill.getCategory().getName() : "Uncategorized",
				bill.getFrequency(),
				due.format(DateTimeFormatter.ISO_LOCAL_DATE),
				bill.getCurrency()
		);
		return new UpcomingBillWithBase(response, amountInBaseCurrency(bill));
	}

	private RecurringExpenseWithBase toResponse(RecurringExpense e, boolean paidThisMonth) {
		RecurringExpenseResponse response = new RecurringExpenseResponse(
				e.getId(),
				e.getName(),
				e.getAmount().setScale(2, RoundingMode.HALF_UP),
				e.getCategory() != null ? e.getCategory().getName() : "Uncategorized",
				e.getFrequency(),
				e.getDayOfMonth(),
				e.getDayOfWeek(),
				e.getMonthOfYear(),
				e.isActive(),
				e.getCurrency(),
				e.getExchangeRate().setScale(4, RoundingMode.HALF_UP),
				paidThisMonth
		);
		return new RecurringExpenseWithBase(response, amountInBaseCurrency(e));
	}

	/**
	 * The amount converted to the base currency, at the {@code NUMERIC(19,4)} scale the stored
	 * {@code amount_in_base_currency} columns hold.
	 *
	 * <p>Deliberately reads {@code e.getAmount()} rather than the two-place amount the response
	 * displays: an amount stored with a third or fourth decimal would otherwise be converted from
	 * a rounded value, and land a centavo away from what {@code ExpenseService} stores for an
	 * expense with the same amount and rate. The conversion itself lives in
	 * {@link Money#toBaseCurrency}, which is the same call {@code ExpenseService} makes.
	 */
	private BigDecimal amountInBaseCurrency(RecurringExpense e) {
		return Money.toBaseCurrency(e.getAmount(), e.getExchangeRate());
	}
}
