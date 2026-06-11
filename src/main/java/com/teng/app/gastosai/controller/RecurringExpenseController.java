package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.RecurringExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.util.List;

@RestController
@RequestMapping("/recurring")
@RequiredArgsConstructor
public class RecurringExpenseController {

	private final RecurringExpenseService recurringExpenseService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RecurringExpenseResponse create(@Valid @RequestBody RecurringExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return recurringExpenseService.create(request, user);
	}

	@GetMapping
	public List<RecurringExpenseResponse> findAll(@AuthenticationPrincipal User user) {
		return recurringExpenseService.findAll(user);
	}

	@PutMapping("/{id}")
	public RecurringExpenseResponse update(@PathVariable Long id,
			@Valid @RequestBody RecurringExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return recurringExpenseService.update(id, request, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		recurringExpenseService.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAll(@AuthenticationPrincipal User user) {
		recurringExpenseService.deleteAll(user);
	}

	@GetMapping("/upcoming")
	public ResponseEntity<?> getUpcoming(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		if (!month.matches("\\d{4}-\\d{2}")) {
			return ResponseEntity.badRequest().body("Invalid month format. Expected YYYY-MM.");
		}
		List<UpcomingBillResponse> results = recurringExpenseService.getUpcoming(month, user);
		return ResponseEntity.ok(results);
	}
}
