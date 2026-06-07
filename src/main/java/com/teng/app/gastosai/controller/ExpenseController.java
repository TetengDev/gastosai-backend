package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.MonthlyReportItem;
import com.teng.app.gastosai.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

	private final ExpenseService expenseService;


	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ExpenseResponse create(@Valid @RequestBody ExpenseRequest request) {
		return expenseService.create(request);
	}

	@GetMapping
	public List<ExpenseResponse> list() {
		return expenseService.findAll();
	}

	@GetMapping("/{id}")
	public ExpenseResponse get(@PathVariable Long id) {
		return expenseService.findById(id);
	}

	@PutMapping("/{id}")
	public ExpenseResponse update(@PathVariable Long id, @Valid @RequestBody ExpenseRequest request) {
		return expenseService.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		expenseService.delete(id);
	}

	@GetMapping("/report/monthly")
	public List<MonthlyReportItem> monthlyReport() {
		return expenseService.monthlyReport();
	}

	@GetMapping("/report/category")
	public List<CategoryReportItem> categoryReport() {
		return expenseService.categoryReport();
	}
}
