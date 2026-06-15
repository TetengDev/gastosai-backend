package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.BudgetResponse;
import com.teng.app.gastosai.dto.BudgetSummaryResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.util.List;

@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class BudgetController {

	private final BudgetService budgetService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BudgetResponse create(@Valid @RequestBody BudgetRequest request,
			@RequestParam(defaultValue = "false") boolean force,
			@AuthenticationPrincipal User user) {
		return budgetService.create(request, user, force);
	}

	@GetMapping
	public List<BudgetResponse> list(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return budgetService.findAllByMonth(month, user);
	}

	@PutMapping("/{id}")
	public BudgetResponse update(@PathVariable Long id,
			@Valid @RequestBody BudgetRequest request,
			@AuthenticationPrincipal User user) {
		return budgetService.update(id, request, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id,
			@AuthenticationPrincipal User user) {
		budgetService.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAll(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		budgetService.deleteAllByUserAndMonth(user, month);
	}

	@GetMapping("/summary")
	public BudgetSummaryResponse summary(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return budgetService.getSummary(month, user);
	}
}
