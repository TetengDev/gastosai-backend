package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.DailyReportItem;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.MonthlyComparisonResponse;
import com.teng.app.gastosai.dto.MonthlyReportItem;
import com.teng.app.gastosai.dto.PageResponse;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.dto.ProjectReportItem;
import com.teng.app.gastosai.dto.ProjectRequest;
import com.teng.app.gastosai.dto.ProjectResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.ExpenseType;
import com.teng.app.gastosai.entity.Project;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.ProjectRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.springframework.cache.annotation.CacheEvict;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ExpenseService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private final ExpenseRepository expenseRepository;
	private final CategoryService categoryService;
	private final ProjectRepository projectRepository;

	/**
	 * Create an expense a client asked for directly, recording the source the client declared.
	 *
	 * <p>A client may only declare a source the server cannot tell apart from a plain manual
	 * write — see {@link ExpenseSource}. Anything else is a 400 rather than a silent downgrade to
	 * {@code MANUAL}: a client that names {@code IMPORT} has misunderstood the field, and a quiet
	 * correction would leave it believing the value it sent is the one stored.
	 */
	// Insights are advisory + writes are infrequent, so evict all insight entries on any change (TTL backstops it).
	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
	@Transactional
	public ExpenseResponse create(ExpenseRequest request, User user) {
		return create(request, user, clientDeclaredSource(request));
	}

	private static ExpenseSource clientDeclaredSource(ExpenseRequest request) {
		String declared = request.source();
		if (declared == null || declared.isBlank()) {
			return ExpenseSource.MANUAL;
		}
		ExpenseSource source;
		try {
			source = ExpenseSource.valueOf(declared.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw badSource(declared);
		}
		if (!source.isClientDeclarable()) {
			throw badSource(declared);
		}
		return source;
	}

	/**
	 * One message for both refusals. Telling a client that {@code IMPORT} exists but is not for it
	 * would only invite the next request to try {@code RECURRING}; the answer it needs is the same
	 * either way — here are the two values you may send.
	 */
	private static ResponseStatusException badSource(String declared) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"source must be MANUAL or RECEIPT_SCAN — got '" + declared + "'.");
	}

	/**
	 * Create an expense on behalf of a route that knows how it got here — quick-add, the assistant,
	 * an import. The {@code source} argument wins over anything on the request.
	 */
	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
	@Transactional
	public ExpenseResponse create(ExpenseRequest request, User user, ExpenseSource source) {
		Categorisation categorisation = categorise(request, user);
		Category category = categorisation.category();
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
				.categoryOverridden(categorisation.overridden())
				.source(source)
				.project(resolveProject(request, user))
				.build();
		return toResponse(expenseRepository.save(expense));
	}

	/**
	 * Persist the expense a parser read out of free text, so quick-add is one round trip.
	 *
	 * <p>The parse itself happens before this call — the LLM round trip must not sit inside the
	 * transaction, holding a pooled connection for the second or two it takes. What lives here is
	 * the decision the two-step flow used to leave to the client: whether the draft is good enough
	 * to keep, and what to say when it is not.
	 *
	 * <p>A draft that is not saveable is rejected with 422 and nothing is written. The parser already
	 * phrases the reason for a human — {@code rejectionMessage} when it has one, {@code hint}
	 * otherwise — so its own words are preferred over a generic message. The amount and description
	 * checks are not redundant with {@code saveable}: {@link ExpenseRequest}'s bean validation never
	 * runs on this path (nothing binds the draft through {@code @Valid}), so a model that claims
	 * {@code saveable} while omitting the amount would otherwise reach the database.
	 *
	 * <p>Category and date are deliberately left to {@link #create}: a null category means the
	 * merchant rules get their say, and a null date means "now", exactly as on the two-step path.
	 */
	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
	@Transactional
	public ExpenseResponse createFromParsed(ParsedExpenseResult draft, User user) {
		if (draft == null) {
			throw unparseable("Could not read an expense from that text.");
		}
		if (!draft.saveable()) {
			throw unparseable(firstNonBlank(draft.rejectionMessage(), draft.hint(),
					"Could not read an expense from that text."));
		}
		if (draft.amount() == null || draft.amount().compareTo(BigDecimal.ZERO) <= 0) {
			throw unparseable("Could not read an amount from that text.");
		}
		if (draft.description() == null || draft.description().isBlank()) {
			throw unparseable("Could not read what the expense was for.");
		}
		ExpenseRequest request = new ExpenseRequest(
				draft.amount(),
				draft.category(),
				draft.date(),
				draft.description(),
				null,
				null,
				null,
				null);
		return create(request, user, ExpenseSource.QUICK_ADD);
	}

	private static ResponseStatusException unparseable(String detail) {
		return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
	}

	private static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isBlank()) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The project or client tag this expense should carry, or null for none.
	 *
	 * <p>Tags are created on first use and matched case-insensitively afterwards, the same bargain
	 * categories strike: a freelancer types "Acme" on the expense form rather than creating a tag
	 * first, and typing "acme" the next week lands on the same tag instead of quietly starting a
	 * second one that splits the engagement's total in half.
	 *
	 * <p>A null or blank name means no tag. On update that is how a tag is removed — a PUT states
	 * the whole expense, so an absent tag means the expense has none, not that the old one stands.
	 */
	private Project resolveProject(ExpenseRequest request, User user) {
		String name = request.project();
		if (name == null || name.isBlank()) {
			return null;
		}
		String trimmed = name.trim();
		return projectRepository.findByUserAndNameIgnoreCase(user, trimmed)
				.orElseGet(() -> projectRepository.save(Project.builder()
						.name(trimmed)
						.user(user)
						.build()));
	}

	/** Every tag this user has, for a filter dropdown or a rename. */
	@Transactional(readOnly = true)
	public List<ProjectResponse> projects(User user) {
		return projectRepository.findAllByUserOrderByNameAsc(user).stream()
				.map(p -> new ProjectResponse(p.getId(), p.getName()))
				.toList();
	}

	/**
	 * Rename a tag, keeping every expense attributed to it.
	 *
	 * <p>The rename is one UPDATE against one row: expenses reference the tag by id, so none of
	 * them is touched, and none of them is orphaned. That is the whole reason the tag is a row
	 * rather than a string on each expense.
	 *
	 * <p>Renaming onto a name the user already has is a 409 rather than a silent merge. Merging two
	 * engagements' history together is not something to infer from a typo, and it cannot be undone
	 * from the outside once the tags are one.
	 */
	@Transactional
	public ProjectResponse renameProject(Long id, ProjectRequest request, User user) {
		Project project = projectRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
		String trimmed = request.name().trim();
		if (trimmed.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
		}
		projectRepository.findByUserAndNameIgnoreCase(user, trimmed)
				.filter(other -> !other.getId().equals(project.getId()))
				.ifPresent(other -> {
					throw new ResponseStatusException(HttpStatus.CONFLICT,
							"A project or client named '" + other.getName() + "' already exists.");
				});
		project.setName(trimmed);
		return new ProjectResponse(project.getId(), projectRepository.save(project).getName());
	}

	/**
	 * What each engagement has cost, newest-costliest first, in the user's base currency.
	 *
	 * <p>Scoped to the caller even for an admin, unlike the category reports: a tag belongs to one
	 * user, so a total summed across users would add up two people's unrelated "Acme" tags into one
	 * meaningless number.
	 */
	@Transactional(readOnly = true)
	public List<ProjectReportItem> projectReport(User user, String month) {
		List<Object[]> rows;
		if (month == null || month.isBlank()) {
			rows = expenseRepository.sumByProject(user);
		} else {
			if (!month.matches("\\d{4}-\\d{2}")) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format");
			}
			YearMonth ym = YearMonth.parse(month);
			rows = expenseRepository.sumByProjectForMonth(user, ym.getYear(), ym.getMonthValue());
		}
		return rows.stream()
				.map(row -> new ProjectReportItem(
						((Number) row[0]).longValue(),
						(String) row[1],
						toBigDecimal(row[2])))
				.toList();
	}

	/** The category this expense lands in, and whether that choice contradicts a merchant rule. */
	private record Categorisation(Category category, boolean overridden) {}

	/**
	 * Decide an expense's category, and keep the merchant rules learning as a side effect.
	 *
	 * <p>No category on the request means "you tell me": a merchant rule answers if one matches,
	 * {@code Uncategorized} otherwise. An explicit category is obeyed either way, but what happens
	 * to the rule differs — with no rule yet, the first hand-categorisation of a merchant
	 * <em>becomes</em> the rule, which is the whole point of the feature; with a rule that says
	 * something else, this is a deliberate one-off and the expense is flagged
	 * {@code categoryOverridden} while the rule is left exactly as it was. Silently rewriting the
	 * rule on every disagreement would mean a single unusual purchase at a familiar shop
	 * re-categorises every future one.
	 */
	private Categorisation categorise(ExpenseRequest request, User user) {
		boolean explicit = request.category() != null && !request.category().isBlank();
		if (!explicit) {
			return new Categorisation(
					categoryService.resolveByMerchant(request.description(), user)
							.orElseGet(() -> categoryService.getOrCreateByName(DEFAULT_CATEGORY, user)),
					false);
		}

		Category chosen = categoryService.getOrCreateByName(request.category(), user);
		Category ruled = categoryService.resolveByMerchant(request.description(), user).orElse(null);
		boolean overridden = ruled != null
				&& ruled.getId() != null
				&& !ruled.getId().equals(chosen.getId());
		if (!overridden) {
			categoryService.learnMerchantRule(request.description(), chosen, user);
		}
		return new Categorisation(chosen, overridden);
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

	/**
	 * Edit an expense. {@code request.source()} is deliberately ignored: the source is a fact about
	 * how the row was created, and correcting an amount a receipt scan misread does not make the
	 * expense manually entered — it makes it a corrected scan, which is exactly what the field
	 * should keep saying.
	 */
	@CacheEvict(cacheNames = {"insightTopCategory", "insightMonthSummary", "insightRecommendations"}, allEntries = true)
	@Transactional
	public ExpenseResponse update(Long id, ExpenseRequest request, User user) {
		Expense expense = (user.isAdmin()
				? expenseRepository.findById(id)
				: expenseRepository.findByIdAndUser(id, user))
				.orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));

		Categorisation categorisation = categorise(request, user);
		expense.setAmount(request.amount());
		expense.setCategory(categorisation.category());
		expense.setCategoryOverridden(categorisation.overridden());
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
		expense.setProject(resolveProject(request, user));
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
		return findAll(user, from, to, null);
	}

	/**
	 * The unfiltered reads keep their derived queries and the source filter goes through a
	 * {@link Specification}, rather than one path for both.
	 *
	 * <p>Source multiplies against the four date shapes and the admin/non-admin split: expressing
	 * it as derived queries would mean twelve more repository methods for one optional parameter.
	 * A specification expresses "and this source too" as one predicate. The unfiltered path is left
	 * exactly as it was because it is the hot one and there is nothing to gain by rewriting it.
	 */
	@Transactional(readOnly = true)
	public List<ExpenseResponse> findAll(User user, LocalDate from, LocalDate to, ExpenseSource source) {
		return findAll(user, from, to, source, null);
	}

	/** @see #findAll(User, LocalDate, LocalDate, ExpenseSource) — plus the project tag filter. */
	@Transactional(readOnly = true)
	public List<ExpenseResponse> findAll(User user, LocalDate from, LocalDate to, ExpenseSource source,
			Long projectId) {
		if (source != null || projectId != null) {
			return expenseRepository
					.findAll(filter(user, from, to, source, projectId), Sort.by(Sort.Direction.DESC, "date"))
					.stream()
					.map(this::toResponse)
					.toList();
		}
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

	private static final int MAX_PAGE_SIZE = 100;
	private static final int DEFAULT_PAGE_SIZE = 50;

	@Transactional(readOnly = true)
	public PageResponse<ExpenseResponse> findPage(User user, LocalDate from, LocalDate to, int page, int size) {
		return findPage(user, from, to, null, page, size);
	}

	/** @see #findAll(User, LocalDate, LocalDate, ExpenseSource) for why source takes its own path. */
	@Transactional(readOnly = true)
	public PageResponse<ExpenseResponse> findPage(User user, LocalDate from, LocalDate to,
			ExpenseSource source, int page, int size) {
		return findPage(user, from, to, source, null, page, size);
	}

	/** @see #findPage(User, LocalDate, LocalDate, ExpenseSource, int, int) — plus the tag filter. */
	@Transactional(readOnly = true)
	public PageResponse<ExpenseResponse> findPage(User user, LocalDate from, LocalDate to,
			ExpenseSource source, Long projectId, int page, int size) {
		int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		int safePage = Math.max(page, 0);
		Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "date"));

		if (source != null || projectId != null) {
			return PageResponse.of(expenseRepository.findAll(filter(user, from, to, source, projectId), pageable)
					.map(this::toResponse));
		}

		Page<Expense> result;
		if (from == null && to == null) {
			result = user.isAdmin()
					? expenseRepository.findAll(pageable)
					: expenseRepository.findByUser(user, pageable);
		} else if (from != null && to != null) {
			LocalDateTime fromDt = from.atStartOfDay();
			LocalDateTime toDt = to.plusDays(1).atStartOfDay();
			result = user.isAdmin()
					? expenseRepository.findByDateGreaterThanEqualAndDateLessThan(fromDt, toDt, pageable)
					: expenseRepository.findByUserAndDateGreaterThanEqualAndDateLessThan(user, fromDt, toDt, pageable);
		} else if (from != null) {
			LocalDateTime fromDt = from.atStartOfDay();
			result = user.isAdmin()
					? expenseRepository.findByDateGreaterThanEqual(fromDt, pageable)
					: expenseRepository.findByUserAndDateGreaterThanEqual(user, fromDt, pageable);
		} else {
			LocalDateTime toDt = to.plusDays(1).atStartOfDay();
			result = user.isAdmin()
					? expenseRepository.findByDateLessThan(toDt, pageable)
					: expenseRepository.findByUserAndDateLessThan(user, toDt, pageable);
		}
		return PageResponse.of(result.map(this::toResponse));
	}

	/**
	 * The same scope, range and ordering the derived queries apply, plus the optional source and
	 * project-tag predicates.
	 *
	 * <p>The date bounds are half-open — {@code [from 00:00, to+1day 00:00)} — so a {@code to} of
	 * the same calendar day includes everything recorded on it, exactly as the unfiltered path
	 * does. An admin is not scoped to a user, mirroring {@code findAll(User)}.
	 *
	 * <p>The tag is matched by id, not by name: a filter a client saved keeps meaning the same
	 * engagement after the tag is renamed.
	 */
	private static Specification<Expense> filter(User user, LocalDate from, LocalDate to,
			ExpenseSource source, Long projectId) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (source != null) {
				predicates.add(cb.equal(root.get("source"), source));
			}
			if (projectId != null) {
				predicates.add(cb.equal(root.get("project").get("id"), projectId));
			}
			if (!user.isAdmin()) {
				predicates.add(cb.equal(root.get("user"), user));
			}
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("date"), from.atStartOfDay()));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.get("date"), to.plusDays(1).atStartOfDay()));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
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
						csvSafe(e.description()),
						csvSafe(e.category()),
						e.amount().toPlainString()
				);
			}
		}
		return sw.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String csvSafe(String v) {
		if (v != null && !v.isEmpty()) {
			char first = v.charAt(0);
			if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
				return "'" + v;
			}
		}
		return v;
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
				e.getAmountInBaseCurrency().setScale(2, RoundingMode.HALF_UP),
				e.getSource(),
				e.getProject() != null ? e.getProject().getId() : null,
				e.getProject() != null ? e.getProject().getName() : null);
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
