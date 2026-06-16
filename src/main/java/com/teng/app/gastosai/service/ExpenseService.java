package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.DailyReportItem;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.MonthlyComparisonResponse;
import com.teng.app.gastosai.dto.MonthlyReportItem;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseType;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.springframework.cache.annotation.CacheEvict;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpenseService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private final ExpenseRepository expenseRepository;
	private final CategoryService categoryService;

	// Insights are advisory + writes are infrequent, so evict all insight entries on any change (TTL backstops it).
	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
	@Transactional
	public ExpenseResponse create(ExpenseRequest request, User user) {
		String categoryName = (request.category() == null || request.category().isBlank())
				? DEFAULT_CATEGORY : request.category();
		Category category = categoryService.getOrCreateByName(categoryName);
		ExpenseType expenseType = request.expenseType() != null
				? ExpenseType.valueOf(request.expenseType())
				: ExpenseType.PERSONAL;
		boolean reimbursable = request.reimbursable() != null && request.reimbursable();
		String currency = (request.currency() == null || request.currency().isBlank()) ? "PHP" : request.currency();
		BigDecimal rate = (request.exchangeRate() == null) ? BigDecimal.ONE : request.exchangeRate();
		BigDecimal base = request.amount().multiply(rate).setScale(4, RoundingMode.HALF_UP);
		Expense expense = Expense.builder()
				.amount(request.amount())
				.user(user)
				.category(category)
				.date(request.date() != null ? request.date() : LocalDateTime.now())
				.description(request.description())
				.expenseType(expenseType)
				.reimbursable(reimbursable)
				.currency(currency)
				.exchangeRate(rate)
				.amountInBaseCurrency(base)
				.build();
		return toResponse(expenseRepository.save(expense));
	}

	@Transactional(readOnly = true)
	public List<ExpenseResponse> findAll(User user) {
		List<Expense> expenses = user.isAdmin()
				? expenseRepository.findAll(Sort.by(Sort.Direction.DESC, "date"))
				: expenseRepository.findAllByUserOrderByDateDesc(user);
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

	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
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
		if (request.expenseType() != null) {
			expense.setExpenseType(ExpenseType.valueOf(request.expenseType()));
		}
		if (request.reimbursable() != null) {
			expense.setReimbursable(request.reimbursable());
		}
		String currency = (request.currency() == null || request.currency().isBlank()) ? "PHP" : request.currency();
		BigDecimal rate = (request.exchangeRate() == null) ? BigDecimal.ONE : request.exchangeRate();
		BigDecimal base = request.amount().multiply(rate).setScale(4, RoundingMode.HALF_UP);
		expense.setCurrency(currency);
		expense.setExchangeRate(rate);
		expense.setAmountInBaseCurrency(base);
		return toResponse(expenseRepository.save(expense));
	}

	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
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

	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
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
	public List<ExpenseResponse> findAll(User user, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return findAll(user);
		}
		List<Expense> expenses;
		if (from != null && to != null) {
			LocalDateTime fromDt = from.atStartOfDay();
			LocalDateTime toDt = to.plusDays(1).atStartOfDay();
			expenses = user.isAdmin()
					? expenseRepository.findAllByDateGreaterThanEqualAndDateLessThanOrderByDateDesc(fromDt, toDt)
					: expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(user, fromDt, toDt);
		} else if (from != null) {
			LocalDateTime fromDt = from.atStartOfDay();
			expenses = user.isAdmin()
					? expenseRepository.findAllByDateGreaterThanEqualOrderByDateDesc(fromDt)
					: expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(user, fromDt);
		} else {
			LocalDateTime toDt = to.plusDays(1).atStartOfDay();
			expenses = user.isAdmin()
					? expenseRepository.findAllByDateLessThanOrderByDateDesc(toDt)
					: expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(user, toDt);
		}
		return expenses.stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public MonthlyComparisonResponse monthlyComparison(User user, String month) {
		String[] parts = month.split("-");
		int year = Integer.parseInt(parts[0]);
		int m = Integer.parseInt(parts[1]);
		int prevYear = (m == 1) ? year - 1 : year;
		int prevMonth = (m == 1) ? 12 : m - 1;

		BigDecimal current = expenseRepository.sumForMonth(user, year, m)
				.setScale(2, RoundingMode.HALF_UP);
		BigDecimal previous = expenseRepository.sumForMonth(user, prevYear, prevMonth)
				.setScale(2, RoundingMode.HALF_UP);

		BigDecimal changePercent = null;
		if (previous.compareTo(BigDecimal.ZERO) != 0) {
			changePercent = current.subtract(previous)
					.divide(previous, 4, RoundingMode.HALF_UP)
					.multiply(new BigDecimal("100"))
					.setScale(2, RoundingMode.HALF_UP);
		}
		return new MonthlyComparisonResponse(month, current, previous, changePercent);
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

	@Transactional(readOnly = true)
	public List<CategoryReportItem> categoryReportForMonth(User user, String month) {
		YearMonth ym = YearMonth.parse(month);
		List<Object[]> rows = user.isAdmin()
				? expenseRepository.sumByCategoryForMonthAll(ym.getYear(), ym.getMonthValue())
				: expenseRepository.sumByCategoryForMonth(user, ym.getYear(), ym.getMonthValue());
		return rows.stream()
				.map(row -> {
					String category = row[0] != null ? (String) row[0] : "Uncategorized";
					return new CategoryReportItem(category, toBigDecimal(row[1]));
				})
				.toList();
	}

	@Transactional(readOnly = true)
	public List<DailyReportItem> dailyReport(User user, String month) {
		if (month == null || !month.matches("\\d{4}-\\d{2}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format");
		}
		YearMonth ym = YearMonth.parse(month);
		List<Object[]> rows = expenseRepository.sumByDayForMonth(user, ym.getYear(), ym.getMonthValue());
		var dayTotals = new java.util.HashMap<Integer, BigDecimal>();
		for (Object[] row : rows) {
			int day = ((Number) row[0]).intValue();
			BigDecimal total = toBigDecimal(row[1]);
			dayTotals.put(day, total);
		}
		return java.util.stream.IntStream.rangeClosed(1, ym.lengthOfMonth())
				.mapToObj(day -> {
					String dateStr = String.format("%04d-%02d-%02d", ym.getYear(), ym.getMonthValue(), day);
					BigDecimal total = dayTotals.getOrDefault(day, BigDecimal.ZERO)
							.setScale(2, RoundingMode.HALF_UP);
					return new DailyReportItem(dateStr, total);
				})
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ExpenseResponse> topTransactions(User user, String month, int limit) {
		if (month == null || !month.matches("\\d{4}-\\d{2}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format");
		}
		int cappedLimit = Math.min(limit, 50);
		YearMonth ym = YearMonth.parse(month);
		LocalDateTime startOfMonth = ym.atDay(1).atStartOfDay();
		LocalDateTime endOfMonth = ym.atEndOfMonth().atTime(23, 59, 59);
		var pageable = PageRequest.of(0, cappedLimit);
		return expenseRepository.findByUserAndDateBetweenOrderByAmountDesc(user, startOfMonth, endOfMonth, pageable)
				.stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public byte[] exportCsv(User user, LocalDate from, LocalDate to) throws IOException {
		List<ExpenseResponse> expenses = findAll(user, from, to);
		StringWriter sw = new StringWriter();
		try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
				.setHeader("Date", "Description", "Category", "Amount")
				.build())) {
			DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
			for (ExpenseResponse e : expenses) {
				printer.printRecord(
						e.date().format(fmt),
						e.description(),
						e.category(),
						e.amount().toPlainString()
				);
			}
		}
		return sw.toString().getBytes(StandardCharsets.UTF_8);
	}

	private ExpenseResponse toResponse(final Expense e) {
		String categoryName = e.getCategory() != null ? e.getCategory().getName() : "Uncategorized";
		return new ExpenseResponse(
				e.getId(),
				e.getAmount().setScale(2, RoundingMode.HALF_UP),
				categoryName,
				e.getDate(),
				e.getDescription(),
				e.getExpenseType().name(),
				e.isReimbursable(),
				e.getCurrency(),
				e.getExchangeRate().setScale(6, RoundingMode.HALF_UP),
				e.getAmountInBaseCurrency().setScale(2, RoundingMode.HALF_UP));
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
