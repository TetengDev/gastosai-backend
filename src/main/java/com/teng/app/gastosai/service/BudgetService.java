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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BudgetService {

	private final BudgetRepository budgetRepository;
	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;

	@Transactional
	public BudgetResponse create(BudgetRequest req, User user) {
		Category category = categoryRepository.findById(req.categoryId())
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + req.categoryId()));

		if (budgetRepository.existsByUserAndCategoryAndMonth(user, category, req.month())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget already exists for this category and month");
		}

		Budget saved = budgetRepository.save(Budget.builder()
				.user(user)
				.category(category)
				.month(req.month())
				.amountLimit(req.amountLimit())
				.build());

		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public List<BudgetResponse> findAllByMonth(String month, User user) {
		return budgetRepository.findAllByUserAndMonth(user, month).stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public BudgetResponse update(Long id, BudgetRequest req, User user) {
		Budget budget = budgetRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Budget not found: " + id));

		budget.setAmountLimit(req.amountLimit());
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

	@Transactional(readOnly = true)
	public BudgetSummaryResponse getSummary(String month, User user) {
		String[] parts = month.split("-");
		int year = Integer.parseInt(parts[0]);
		int monthInt = Integer.parseInt(parts[1]);

		List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);

		List<Object[]> spentRows = expenseRepository.sumByCategoryAndMonth(user, year, monthInt);
		Map<Long, BigDecimal> spentByCategory = spentRows.stream()
				.collect(Collectors.toMap(
						row -> ((Number) row[0]).longValue(),
						row -> toBigDecimal(row[1])
				));

		List<BudgetSummaryItem> items = budgets.stream().map(b -> {
			BigDecimal budgeted = b.getAmountLimit().setScale(2, RoundingMode.HALF_UP);
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
				b.getAmountLimit().setScale(2, RoundingMode.HALF_UP)
		);
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value == null) return BigDecimal.ZERO;
		if (value instanceof BigDecimal bd) return bd;
		if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
		return new BigDecimal(value.toString());
	}
}
