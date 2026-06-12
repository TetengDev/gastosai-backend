package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.DailyReportItem;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.dto.MonthlyComparisonResponse;
import com.teng.app.gastosai.dto.MonthlyReportItem;
import com.teng.app.gastosai.dto.ParseExpenseRequest;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.CsvImportService;
import com.teng.app.gastosai.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

	private final ExpenseService expenseService;
	private final CsvImportService csvImportService;
	private final ExpenseParser expenseParser;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ExpenseResponse create(@Valid @RequestBody ExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return expenseService.create(request, user);
	}

	@GetMapping
	public List<ExpenseResponse> list(@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@AuthenticationPrincipal User user) {
		return expenseService.findAll(user, from, to);
	}

	@GetMapping("/{id}")
	public ExpenseResponse get(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return expenseService.findById(id, user);
	}

	@PutMapping("/{id}")
	public ExpenseResponse update(@PathVariable Long id,
			@Valid @RequestBody ExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return expenseService.update(id, request, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		expenseService.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAll(@AuthenticationPrincipal User user) {
		expenseService.deleteAll(user);
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> export(
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@AuthenticationPrincipal User user) throws IOException {
		byte[] csv = expenseService.exportCsv(user, from, to);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"expenses.csv\"")
				.contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
				.body(csv);
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ImportResult importCsv(@RequestParam("file") MultipartFile file,
			@AuthenticationPrincipal User user) {
		try {
			return csvImportService.importCsv(file, user);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read CSV: " + e.getMessage());
		}
	}

	@PostMapping("/parse")
	public ParsedExpenseResult parse(@Valid @RequestBody ParseExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return expenseParser.parse(request.text());
	}

	@GetMapping("/report/monthly")
	public List<MonthlyReportItem> monthlyReport(@AuthenticationPrincipal User user) {
		return expenseService.monthlyReport(user);
	}

	@GetMapping("/report/monthly-comparison")
	public MonthlyComparisonResponse monthlyComparison(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		if (!month.matches("\\d{4}-\\d{2}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be in YYYY-MM format");
		}
		return expenseService.monthlyComparison(user, month);
	}

	@GetMapping("/report/category")
	public List<CategoryReportItem> categoryReport(@AuthenticationPrincipal User user) {
		return expenseService.categoryReport(user);
	}

	@GetMapping("/report/daily")
	public ResponseEntity<List<DailyReportItem>> dailyReport(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return ResponseEntity.ok(expenseService.dailyReport(user, month));
	}

	@GetMapping("/report/top")
	public ResponseEntity<List<ExpenseResponse>> topTransactions(
			@RequestParam String month,
			@RequestParam(defaultValue = "5") int limit,
			@AuthenticationPrincipal User user) {
		int cappedLimit = Math.min(limit, 50);
		return ResponseEntity.ok(expenseService.topTransactions(user, month, cappedLimit));
	}
}
