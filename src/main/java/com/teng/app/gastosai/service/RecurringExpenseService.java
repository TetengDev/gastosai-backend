package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional
	public RecurringExpenseResponse create(RecurringExpenseRequest req, User user) {
		String categoryName = (req.categoryName() != null && !req.categoryName().isBlank())
				? req.categoryName()
				: "Uncategorized";
		Category category = categoryService.getOrCreateByName(categoryName);

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
		return recurringExpenseRepository.findAllByUser(user).stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public RecurringExpenseResponse update(Long id, RecurringExpenseRequest req, User user) {
		RecurringExpense expense = recurringExpenseRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("RecurringExpense not found: " + id));

		String categoryName = (req.categoryName() != null && !req.categoryName().isBlank())
				? req.categoryName()
				: "Uncategorized";
		Category category = categoryService.getOrCreateByName(categoryName);

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
		YearMonth yearMonth = YearMonth.parse(month);
		int year = yearMonth.getYear();
		int monthValue = yearMonth.getMonthValue();

		List<RecurringExpense> bills = recurringExpenseRepository.findAllByUserAndActiveTrue(user);
		List<UpcomingBillResponse> results = new ArrayList<>();

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

		results.sort(Comparator.comparing(UpcomingBillResponse::dueDate));
		return results;
	}

	private UpcomingBillResponse toUpcomingResponse(RecurringExpense bill, LocalDate due) {
		return new UpcomingBillResponse(
				bill.getId(),
				bill.getName(),
				bill.getAmount().setScale(2, RoundingMode.HALF_UP),
				bill.getCategory() != null ? bill.getCategory().getName() : "Uncategorized",
				bill.getFrequency(),
				due.format(DateTimeFormatter.ISO_LOCAL_DATE),
				bill.getCurrency()
		);
	}

	private RecurringExpenseResponse toResponse(RecurringExpense e) {
		return new RecurringExpenseResponse(
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
	}
}
