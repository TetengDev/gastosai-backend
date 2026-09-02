package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd"),
			DateTimeFormatter.ofPattern("MM/dd/yyyy"),
			DateTimeFormatter.ofPattern("M/d/yyyy"),
			DateTimeFormatter.ofPattern("dd/MM/yyyy"),
			DateTimeFormatter.ofPattern("d/M/yyyy")
	);

	private final ExpenseRepository expenseRepository;
	private final CategoryService categoryService;
	private final PlatformTransactionManager transactionManager;

	// Abuse limits — a 10 MB upload could otherwise carry hundreds of thousands of rows
	// (unbounded memory + DB/category storage amplification, CWE-770) or multi-megabyte
	// single fields into the unbounded `description` TEXT column. Field initializers are the
	// defaults used in unit tests (@Value is only applied under Spring).
	@Value("${gastos.import.max-rows:10000}")
	private int maxRows = 10000;
	@Value("${gastos.import.max-description-length:500}")
	private int maxDescriptionLength = 500;
	@Value("${gastos.import.max-category-length:100}")
	private int maxCategoryLength = 100;

	private record PendingRow(int rowNum, BigDecimal amount, String categoryName, String description,
			LocalDateTime date) {
	}

	public ImportResult importCsv(MultipartFile file, User user) throws IOException {
		return importCsv(file, user, false);
	}

	/**
	 * Lenient (default): valid rows import, invalid amounts skip, non-numeric rows error — best-effort.
	 * Strict: any skip or error rejects the whole file (nothing persisted), with a per-row reason list;
	 * valid files persist all rows in a single transaction.
	 *
	 * <p>The plan category cap parts the two: lenient finishes the file and reports the refusal in
	 * {@link ImportResult#limitReached()}; strict lets it escape, so the file rolls back and the
	 * request answers 402 naming {@code CUSTOM_CATEGORIES}.
	 */
	public ImportResult importCsv(MultipartFile file, User user, boolean strict) throws IOException {
		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setIgnoreHeaderCase(true)
				.setTrim(true)
				.setIgnoreEmptyLines(true)
				.build();

		List<PendingRow> pending = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		int skipped = 0;
		int rowNum = 1;
		boolean overCap = false;

		try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
			for (CSVRecord record : format.parse(reader)) {
				rowNum++;
				// Row cap: stop reading past the limit so a huge file can't exhaust memory.
				if (rowNum - 1 > maxRows) {
					overCap = true;
					break;
				}
				String amountRaw = col(record, "amount");
				if (amountRaw == null) {
					if (strict) errors.add("Row " + rowNum + ": missing amount");
					else skipped++;
					continue;
				}
				BigDecimal amount;
				try {
					amount = new BigDecimal(amountRaw.replaceAll("[^\\d.-]", ""));
				} catch (NumberFormatException e) {
					errors.add("Row " + rowNum + ": invalid amount '" + amountRaw + "'");
					continue;
				}
				if (amount.compareTo(BigDecimal.ZERO) <= 0) {
					if (strict) errors.add("Row " + rowNum + ": amount must be greater than 0");
					else skipped++;
					continue;
				}
				pending.add(new PendingRow(
						rowNum,
						amount,
						clamp(firstNonBlank(col(record, "category"), DEFAULT_CATEGORY), maxCategoryLength),
						clamp(firstNonBlank(col(record, "description"), col(record, "note"), ""), maxDescriptionLength),
						parseDate(firstNonBlank(col(record, "date"), col(record, "datetime")))));
			}
		}

		// Row cap hit: reject the whole file (no partial import) with a clear, non-leaky message.
		if (overCap) {
			return new ImportResult(0, 0, List.of(
					"File exceeds the maximum of " + maxRows + " rows. Nothing was imported — split it into smaller files."));
		}

		// Strict: reject the whole file if anything was wrong — persist nothing.
		if (strict && !errors.isEmpty()) {
			return new ImportResult(0, 0, errors);
		}

		if (strict) {
			final List<PendingRow> rows = pending;
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> rows.forEach(r -> persist(r, user)));
			return new ImportResult(pending.size(), 0, errors);
		}

		int imported = 0;
		String limitReached = null;
		for (PendingRow row : pending) {
			try {
				persist(row, user);
				imported++;
			} catch (FeatureLockedException e) {
				// The plan category cap (TEN-319, TEN-327), reached because a row named a category
				// the user does not have yet. Non-strict finishes the file (TEN-329): rethrowing
				// here answered 402 and threw away every row after the first refusal, including the
				// rows naming categories the user already has — which import fine, since resolving
				// an existing category is never capped. A partial import behind a 402 that reports
				// no count is the worst of both, so the refusal is now per row.
				//
				// The rows that needed a new category are listed in `errors`, and `limitReached`
				// carries the feature key so a client can route to an upgrade prompt instead of
				// parsing prose — the entitlement is not a defect in row N, which is why an error
				// string alone was rejected in TEN-327.
				//
				// Rows already imported stay imported — non-strict mode commits row by row and
				// always has. A caller that wants all-or-nothing passes strict=true, where the
				// same refusal rolls the whole file back inside the TransactionTemplate above and
				// still answers 402 naming CUSTOM_CATEGORIES.
				if (limitReached == null) {
					log.warn("csv_import_category_cap_reached for user {}", user.getId());
				}
				limitReached = e.getFeature().name();
				errors.add("Row " + row.rowNum() + ": category '" + row.categoryName()
						+ "' would be a new category and your plan has no room for it. Upgrade, or map this row"
						+ " to a category you already have.");
			} catch (Exception e) {
				// Don't leak the raw DB/exception text to the user.
				log.warn("csv_import_row_save_failed: {}", e.getMessage());
				errors.add("A row could not be saved.");
			}
		}
		return new ImportResult(imported, skipped, errors, limitReached);
	}

	public byte[] buildTemplate() throws IOException {
		StringWriter sw = new StringWriter();
		try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
				.setHeader("date", "amount", "category", "description").build())) {
			printer.printRecord("2026-06-15", "250.00", "Food", "Lunch");
			printer.printRecord("2026-06-16", "1200.50", "Transportation", "Grab to office");
		}
		return sw.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void persist(PendingRow row, User user) {
		Category category = categoryService.getOrCreateByName(row.categoryName(), user);
		expenseRepository.save(Expense.builder()
				.amount(row.amount())
				.user(user)
				.category(category)
				.date(row.date())
				.description(row.description())
				.currency("PHP")
				.exchangeRate(BigDecimal.ONE)
				.amountInBaseCurrency(row.amount())
				.source(ExpenseSource.IMPORT)
				.build());
	}

	private static String col(CSVRecord record, String name) {
		try {
			String v = record.get(name);
			return (v != null && !v.isBlank()) ? v.trim() : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String clamp(String s, int max) {
		return (s != null && s.length() > max) ? s.substring(0, max) : s;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) return v.trim();
		}
		return "";
	}

	private static LocalDateTime parseDate(String raw) {
		if (raw == null || raw.isBlank()) return LocalDateTime.now();
		for (DateTimeFormatter fmt : DATE_FORMATS) {
			try {
				if (fmt.toString().contains("HH")) {
					return LocalDateTime.parse(raw, fmt);
				}
				return LocalDate.parse(raw, fmt).atStartOfDay();
			} catch (DateTimeParseException ignored) {}
		}
		return LocalDateTime.now();
	}
}
