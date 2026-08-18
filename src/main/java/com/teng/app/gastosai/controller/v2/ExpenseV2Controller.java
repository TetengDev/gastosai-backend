package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.controller.ExpenseController;
import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.dto.PageResponse;
import com.teng.app.gastosai.dto.ParseExpenseRequest;
import com.teng.app.gastosai.dto.v2.CategoryReportItemV2;
import com.teng.app.gastosai.dto.v2.DailyReportItemV2;
import com.teng.app.gastosai.dto.v2.ExpenseRequestV2;
import com.teng.app.gastosai.dto.v2.ExpenseResponseV2;
import com.teng.app.gastosai.dto.v2.MonthlyComparisonResponseV2;
import com.teng.app.gastosai.dto.v2.MonthlyReportItemV2;
import com.teng.app.gastosai.dto.v2.ParsedExpenseResultV2;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** {@link ExpenseController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/expenses")
@RequiredArgsConstructor
public class ExpenseV2Controller {

	private final ExpenseController delegate;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateExpense")
	public ExpenseResponseV2 create(@Valid @RequestBody ExpenseRequestV2 request,
			@AuthenticationPrincipal User user) {
		return ExpenseResponseV2.from(delegate.create(request.toV1(), user));
	}

	@GetMapping
	@Operation(operationId = "v2ListExpenses")
	public List<ExpenseResponseV2> list(@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@AuthenticationPrincipal User user) {
		return delegate.list(from, to, user).stream().map(ExpenseResponseV2::from).toList();
	}

	@GetMapping("/page")
	@Operation(operationId = "v2PageExpenses")
	public PageResponse<ExpenseResponseV2> page(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@AuthenticationPrincipal User user) {
		PageResponse<com.teng.app.gastosai.dto.ExpenseResponse> v1 =
				delegate.page(page, size, from, to, user);
		return new PageResponse<>(
				v1.content().stream().map(ExpenseResponseV2::from).toList(),
				v1.page(), v1.size(), v1.totalElements(), v1.totalPages(), v1.last());
	}

	@GetMapping("/{id}")
	@Operation(operationId = "v2GetExpense")
	public ExpenseResponseV2 get(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return ExpenseResponseV2.from(delegate.get(id, user));
	}

	@PutMapping("/{id}")
	@Operation(operationId = "v2UpdateExpense")
	public ExpenseResponseV2 update(@PathVariable Long id,
			@Valid @RequestBody ExpenseRequestV2 request,
			@AuthenticationPrincipal User user) {
		return ExpenseResponseV2.from(delegate.update(id, request.toV1(), user));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteExpense")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteAllExpenses")
	public void deleteAll(@AuthenticationPrincipal User user) {
		delegate.deleteAll(user);
	}

	/**
	 * CSV, not JSON, so the export is byte-identical to v1's on purpose: a spreadsheet column is a
	 * display surface for a human, and {@code 150.75} is what belongs in it. Integer centavos are
	 * the wire representation for clients that compute, not for a file someone opens in Excel.
	 */
	@GetMapping("/export")
	@RequiresFeature(FeatureKey.EXPORT_CSV)
	@Operation(operationId = "v2ExportExpenses")
	public ResponseEntity<byte[]> export(
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@AuthenticationPrincipal User user) throws IOException {
		return delegate.export(from, to, user);
	}

	/** Decimal amounts in the uploaded CSV, for the same reason {@link #export} emits them. */
	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "v2ImportExpenses")
	public ImportResult importCsv(@RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "false") boolean strict,
			@AuthenticationPrincipal User user) {
		return delegate.importCsv(file, strict, user);
	}

	@GetMapping("/import/template")
	@Operation(operationId = "v2ImportTemplate")
	public ResponseEntity<byte[]> importTemplate() throws IOException {
		return delegate.importTemplate();
	}

	@PostMapping("/parse")
	@Operation(operationId = "v2ParseExpense")
	public ParsedExpenseResultV2 parse(@Valid @RequestBody ParseExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return ParsedExpenseResultV2.from(delegate.parse(request, user));
	}

	@GetMapping("/report/monthly")
	@Operation(operationId = "v2MonthlyReport")
	public List<MonthlyReportItemV2> monthlyReport(@AuthenticationPrincipal User user) {
		return delegate.monthlyReport(user).stream().map(MonthlyReportItemV2::from).toList();
	}

	@GetMapping("/report/monthly-comparison")
	@Operation(operationId = "v2MonthlyComparison")
	public MonthlyComparisonResponseV2 monthlyComparison(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return MonthlyComparisonResponseV2.from(delegate.monthlyComparison(month, user));
	}

	@GetMapping("/report/category")
	@Operation(operationId = "v2CategoryReport")
	public List<CategoryReportItemV2> categoryReport(@AuthenticationPrincipal User user) {
		return delegate.categoryReport(user).stream().map(CategoryReportItemV2::from).toList();
	}

	@GetMapping("/report/daily")
	@Operation(operationId = "v2DailyReport")
	public ResponseEntity<List<DailyReportItemV2>> dailyReport(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return ResponseEntity.ok(delegate.dailyReport(month, user).getBody().stream()
				.map(DailyReportItemV2::from).toList());
	}

	@GetMapping("/report/top")
	@Operation(operationId = "v2TopTransactions")
	public ResponseEntity<List<ExpenseResponseV2>> topTransactions(
			@RequestParam String month,
			@RequestParam(defaultValue = "5") int limit,
			@AuthenticationPrincipal User user) {
		return ResponseEntity.ok(delegate.topTransactions(month, limit, user).getBody().stream()
				.map(ExpenseResponseV2::from).toList());
	}
}
