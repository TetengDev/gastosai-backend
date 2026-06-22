package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
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

	private record PendingRow(BigDecimal amount, String categoryName, String description, LocalDateTime date) {
	}

	public ImportResult importCsv(MultipartFile file, User user) throws IOException {
		return importCsv(file, user, false);
	}

	/**
	 * Lenient (default): valid rows import, invalid amounts skip, non-numeric rows error — best-effort.
	 * Strict: any skip or error rejects the whole file (nothing persisted), with a per-row reason list;
	 * valid files persist all rows in a single transaction.
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

		try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
			for (CSVRecord record : format.parse(reader)) {
				rowNum++;
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
						amount,
						firstNonBlank(col(record, "category"), DEFAULT_CATEGORY),
						firstNonBlank(col(record, "description"), col(record, "note"), ""),
						parseDate(firstNonBlank(col(record, "date"), col(record, "datetime")))));
			}
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
		for (PendingRow row : pending) {
			try {
				persist(row, user);
				imported++;
			} catch (Exception e) {
				// Don't leak the raw DB/exception text to the user.
				log.warn("csv_import_row_save_failed: {}", e.getMessage());
				errors.add("A row could not be saved.");
			}
		}
		return new ImportResult(imported, skipped, errors);
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
