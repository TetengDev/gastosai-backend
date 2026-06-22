package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.BudgetResponse;
import com.teng.app.gastosai.dto.BudgetSummaryItem;
import com.teng.app.gastosai.dto.BudgetSummaryResponse;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BudgetService {

	private final BudgetRepository budgetRepository;
	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;

	@Transactional
	public BudgetResponse create(BudgetRequest req, User user) {
		return create(req, user, false);
	}

	@Transactional
	public BudgetResponse create(BudgetRequest req, User user, boolean force) {
		Category category = categoryRepository.findByIdAndUser(req.categoryId(), user)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + req.categoryId()));

		Optional<Budget> existing = budgetRepository.findByUserAndCategoryAndMonth(user, category, req.month());
		if (existing.isPresent() && !force) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget already exists for this category and month");
		}

		String currency = req.currency() != null ? req.currency() : "PHP";
		BigDecimal rate = req.exchangeRate() != null ? req.exchangeRate() : BigDecimal.ONE;

		Budget budget = existing.orElseGet(Budget::new);
		budget.setUser(user);
		budget.setCategory(category);
		budget.setMonth(req.month());
		budget.setAmountLimit(req.amountLimit());
		budget.setCurrency(currency);
		budget.setExchangeRate(rate);
		budget.setAmountLimitInBaseCurrency(req.amountLimit().multiply(rate));
		budget.setRecurring(Boolean.TRUE.equals(req.recurring()));

		return toResponse(budgetRepository.save(budget));
	}

	@Transactional
	public List<BudgetResponse> findAllByMonth(String month, User user) {
		materializeRecurring(user, month);
		return budgetRepository.findAllByUserAndMonth(user, month).stream()
				.map(this::toResponse)
				.toList();
	}

	/**
	 * Carry recurring budgets forward: for each category whose most recent budget (in any month up to
	 * {@code month}) is marked recurring, create a copy for {@code month} if one does not already
	 * exist. Lazy — runs when a month is first viewed. Turning recurring off on a later month stops
	 * the carry-forward because the most recent row is then non-recurring.
	 */
	private void materializeRecurring(User user, String month) {
		// Storage-amplification guard: only auto-create rows up to next month. Without this an
		// authenticated user could request arbitrary far-future months and have a row written for
		// every recurring category on each read.
		YearMonth target;
		try {
			target = YearMonth.parse(month);
		} catch (DateTimeParseException ex) {
			return;
		}
		if (target.isAfter(YearMonth.now().plusMonths(1))) {
			return;
		}
		List<Budget> all = budgetRepository.findAllByUser(user);
		Map<Long, Budget> latestUpToMonth = new HashMap<>();
		Set<Long> categoriesWithThisMonth = new HashSet<>();
		for (Budget b : all) {
			Long categoryId = b.getCategory().getId();
			if (b.getMonth().equals(month)) {
				categoriesWithThisMonth.add(categoryId);
			}
			if (b.getMonth().compareTo(month) <= 0) {
				latestUpToMonth.merge(categoryId, b,
						(x, y) -> x.getMonth().compareTo(y.getMonth()) >= 0 ? x : y);
			}
		}
		for (Budget latest : latestUpToMonth.values()) {
			if (latest.isRecurring() && !categoriesWithThisMonth.contains(latest.getCategory().getId())) {
				budgetRepository.save(Budget.builder()
						.user(user)
						.category(latest.getCategory())
						.month(month)
						.amountLimit(latest.getAmountLimit())
						.currency(latest.getCurrency())
						.exchangeRate(latest.getExchangeRate())
						.amountLimitInBaseCurrency(latest.getAmountLimitInBaseCurrency())
						.recurring(true)
						.build());
			}
		}
	}

	@Transactional
	public BudgetResponse update(Long id, BudgetRequest req, User user) {
		Budget budget = budgetRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Budget not found: " + id));

		String currency = req.currency() != null ? req.currency() : "PHP";
		BigDecimal rate = req.exchangeRate() != null ? req.exchangeRate() : BigDecimal.ONE;
		budget.setAmountLimit(req.amountLimit());
		budget.setCurrency(currency);
		budget.setExchangeRate(rate);
		budget.setAmountLimitInBaseCurrency(req.amountLimit().multiply(rate));
		if (req.recurring() != null) {
			budget.setRecurring(req.recurring());
		}
		return toResponse(budgetRepository.save(budget));
	}

	@Transactional
	public void delete(Long id, User user) {
		Budget budget = budgetRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Budget not found: " + id));
		budgetRepository.delete(budget);
	}

	@Transactional
	public void deleteAllByUserAndMonth(User user, String month) {
		budgetRepository.deleteAll(budgetRepository.findAllByUserAndMonth(user, month));
	}

	@Transactional
	public BudgetSummaryResponse getSummary(String month, User user) {
		YearMonth yearMonth = parseMonth(month);
		int year = yearMonth.getYear();
		int monthInt = yearMonth.getMonthValue();

		materializeRecurring(user, month);
		List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);

		List<Object[]> spentRows = expenseRepository.sumByCategoryAndMonth(user, year, monthInt);
		Map<Long, BigDecimal> spentByCategory = spentRows.stream()
				.collect(Collectors.toMap(
						row -> ((Number) row[0]).longValue(),
						row -> toBigDecimal(row[1])
				));

		List<BudgetSummaryItem> items = budgets.stream().map(b -> {
			BigDecimal budgeted = b.getAmountLimitInBaseCurrency().setScale(2, RoundingMode.HALF_UP);
			BigDecimal spent = spentByCategory.getOrDefault(b.getCategory().getId(), BigDecimal.ZERO)
					.setScale(2, RoundingMode.HALF_UP);
			BigDecimal remaining = budgeted.subtract(spent);
			BigDecimal percentUsed = budgeted.compareTo(BigDecimal.ZERO) == 0
					? BigDecimal.ZERO
					: spent.divide(budgeted, 4, RoundingMode.HALF_UP)
							.multiply(BigDecimal.valueOf(100))
							.setScale(2, RoundingMode.HALF_UP);

			String status;
			if (percentUsed.compareTo(BigDecimal.valueOf(100)) >= 0) {
				status = "OVER_BUDGET";
			} else if (percentUsed.compareTo(BigDecimal.valueOf(80)) >= 0) {
				status = "WARNING";
			} else {
				status = "ON_TRACK";
			}

			return new BudgetSummaryItem(
					b.getCategory().getId(),
					b.getCategory().getName(),
					budgeted,
					spent,
					remaining.setScale(2, RoundingMode.HALF_UP),
					percentUsed,
					status
			);
		}).toList();

		BigDecimal totalBudgeted = items.stream()
				.map(BudgetSummaryItem::budgeted)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.HALF_UP);

		BigDecimal totalSpent = items.stream()
				.map(BudgetSummaryItem::spent)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.HALF_UP);

		BigDecimal safeToSpend = totalBudgeted.subtract(totalSpent).setScale(2, RoundingMode.HALF_UP);

		BigDecimal dailyAllowance = computeDailyAllowance(month, year, monthInt, safeToSpend);

		return new BudgetSummaryResponse(month, items, totalBudgeted, totalSpent, safeToSpend, dailyAllowance);
	}

	private static YearMonth parseMonth(String month) {
		try {
			return YearMonth.parse(month);
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("Invalid month, expected format YYYY-MM: " + month);
		}
	}

	private BigDecimal computeDailyAllowance(String month, int year, int monthInt, BigDecimal safeToSpend) {
		YearMonth budgetYearMonth = YearMonth.of(year, monthInt);
		YearMonth currentYearMonth = YearMonth.now();

		if (budgetYearMonth.isBefore(currentYearMonth)) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}

		int remainingDays;
		if (budgetYearMonth.equals(currentYearMonth)) {
			remainingDays = budgetYearMonth.lengthOfMonth() - LocalDate.now().getDayOfMonth();
		} else {
			remainingDays = budgetYearMonth.lengthOfMonth();
		}

		if (safeToSpend.compareTo(BigDecimal.ZERO) <= 0 || remainingDays == 0) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}

		return safeToSpend.divide(BigDecimal.valueOf(remainingDays), 2, RoundingMode.HALF_UP);
	}

	private BudgetResponse toResponse(Budget b) {
		return new BudgetResponse(
				b.getId(),
				b.getCategory().getId(),
				b.getCategory().getName(),
				b.getMonth(),
				b.getAmountLimit().setScale(2, RoundingMode.HALF_UP),
				b.getCurrency(),
				b.getExchangeRate().setScale(4, RoundingMode.HALF_UP),
				b.getAmountLimitInBaseCurrency().setScale(2, RoundingMode.HALF_UP),
				b.isRecurring()
		);
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value == null) return BigDecimal.ZERO;
		if (value instanceof BigDecimal bd) return bd;
		if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
		return new BigDecimal(value.toString());
	}
}
