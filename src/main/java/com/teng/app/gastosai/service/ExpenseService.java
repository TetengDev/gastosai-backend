package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.MonthlyReportItem;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpenseService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private final ExpenseRepository expenseRepository;
	private final CategoryService categoryService;

	@Transactional
	public ExpenseResponse create(ExpenseRequest request, User user) {
		String categoryName = (request.category() == null || request.category().isBlank())
				? DEFAULT_CATEGORY : request.category();
		Category category = categoryService.getOrCreateByName(categoryName);
		Expense expense = Expense.builder()
				.amount(request.amount())
				.user(user)
				.category(category)
				.date(request.date() != null ? request.date() : LocalDateTime.now())
				.description(request.description())
				.build();
		return toResponse(expenseRepository.save(expense));
	}

	@Transactional(readOnly = true)
	public List<ExpenseResponse> findAll(User user) {
		List<Expense> expenses = user.isAdmin()
				? expenseRepository.findAll()
				: expenseRepository.findAllByUser(user);
		return expenses.stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public ExpenseResponse findById(Long id, User user) {
		return (user.isAdmin()
				? expenseRepository.findById(id)
				: expenseRepository.findByIdAndUser(id, user))
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
	}

	@Transactional
	public ExpenseResponse update(Long id, ExpenseRequest request, User user) {
		Expense expense = (user.isAdmin()
				? expenseRepository.findById(id)
				: expenseRepository.findByIdAndUser(id, user))
				.orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

		String categoryName = (request.category() == null || request.category().isBlank())
				? DEFAULT_CATEGORY : request.category();
		Category category = categoryService.getOrCreateByName(categoryName);
		expense.setAmount(request.amount());
		expense.setCategory(category);
		expense.setDate(request.date() != null ? request.date() : expense.getDate());
		expense.setDescription(request.description());
		return toResponse(expenseRepository.save(expense));
	}

	@Transactional
	public void delete(Long id, User user) {
		if (user.isAdmin()) {
			if (!expenseRepository.existsById(id)) {
				throw new ResourceNotFoundException("Expense not found: " + id);
			}
		} else if (!expenseRepository.existsByIdAndUser(id, user)) {
			throw new ResourceNotFoundException("Expense not found: " + id);
		}
		expenseRepository.deleteById(id);
	}

	@Transactional
	public void deleteAll(User user) {
		if (!user.isAdmin()) {
			expenseRepository.deleteAllByUser(user);
		}
	}

	@Transactional(readOnly = true)
	public List<MonthlyReportItem> monthlyReport(User user) {
		List<Object[]> rows = user.isAdmin()
				? expenseRepository.sumByYearMonthAll()
				: expenseRepository.sumByYearMonth(user);
		return rows.stream()
				.map(row -> {
					int year = ((Number) row[0]).intValue();
					int month = ((Number) row[1]).intValue();
					BigDecimal total = toBigDecimal(row[2]);
					return new MonthlyReportItem(String.format("%04d-%02d", year, month), total);
				})
				.toList();
	}

	@Transactional(readOnly = true)
	public List<CategoryReportItem> categoryReport(User user) {
		List<Object[]> rows = user.isAdmin()
				? expenseRepository.sumByCategoryAll()
				: expenseRepository.sumByCategory(user);
		return rows.stream()
				.map(row -> {
					String category = row[0] != null ? (String) row[0] : "Uncategorized";
					return new CategoryReportItem(category, toBigDecimal(row[1]));
				})
				.toList();
	}

	private ExpenseResponse toResponse(final Expense e) {
		String categoryName = e.getCategory() != null ? e.getCategory().getName() : "Uncategorized";
		return new ExpenseResponse(e.getId(), e.getAmount().setScale(2, RoundingMode.HALF_UP), categoryName, e.getDate(), e.getDescription());
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		if (value instanceof BigDecimal bd) {
			return bd.setScale(2, RoundingMode.HALF_UP);
		}
		if (value instanceof Number n) {
			return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
		}
		return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
	}
}
