package com.teng.app.gastosai.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecurringExpenseService {

	private final RecurringExpenseRepository recurringExpenseRepository;
	private final CategoryService categoryService;

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

		return toResponse(saved);
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
		return recurringExpenseRepository.findAllByUser(user).stream()
				.map(this::toResponse)
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

		return toResponse(recurringExpenseRepository.save(expense));
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

	private RecurringExpenseWithBase toResponse(RecurringExpense e) {
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
				e.getExchangeRate().setScale(4, RoundingMode.HALF_UP)
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
	 * expense with the same amount and rate. Same expression, same rounding mode, as that method.
	 */
	private BigDecimal amountInBaseCurrency(RecurringExpense e) {
		BigDecimal rate = e.getExchangeRate() != null ? e.getExchangeRate() : BigDecimal.ONE;
		return e.getAmount().multiply(rate).setScale(4, RoundingMode.HALF_UP);
	}
}
