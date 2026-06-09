package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

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

	public ImportResult importCsv(MultipartFile file, User user) throws IOException {
		int imported = 0;
		int skipped = 0;
		List<String> errors = new ArrayList<>();
		int rowNum = 1;

		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setIgnoreHeaderCase(true)
				.setTrim(true)
				.setIgnoreEmptyLines(true)
				.build();

		try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
			for (CSVRecord record : format.parse(reader)) {
				rowNum++;
				try {
					String amountRaw = col(record, "amount");
					if (amountRaw == null) {
						skipped++;
						continue;
					}
					BigDecimal amount = new BigDecimal(amountRaw.replaceAll("[^\\d.-]", ""));
					if (amount.compareTo(BigDecimal.ZERO) <= 0) {
						skipped++;
						continue;
					}

					String categoryName = firstNonBlank(col(record, "category"), DEFAULT_CATEGORY);
					Category category = categoryService.getOrCreateByName(categoryName);

					String description = firstNonBlank(col(record, "description"), col(record, "note"), "");
					LocalDateTime date = parseDate(firstNonBlank(col(record, "date"), col(record, "datetime")));

					expenseRepository.save(Expense.builder()
							.amount(amount)
							.user(user)
							.category(category)
							.date(date)
							.description(description)
							.build());
					imported++;
				} catch (Exception e) {
					errors.add("Row " + rowNum + ": " + e.getMessage());
				}
			}
		}

		return new ImportResult(imported, skipped, errors);
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
