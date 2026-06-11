package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.SavingsGoalService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/goals")
@RequiredArgsConstructor
public class SavingsGoalController {

	private final SavingsGoalService savingsGoalService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public GoalResponse create(@Valid @RequestBody GoalRequest request,
			@AuthenticationPrincipal User user) {
		return savingsGoalService.create(request, user);
	}

	@GetMapping
	public List<GoalResponse> list(@AuthenticationPrincipal User user) {
		return savingsGoalService.findAll(user);
	}

	@GetMapping("/{id}")
	public GoalResponse get(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return savingsGoalService.findById(id, user);
	}

	@PutMapping("/{id}")
	public GoalResponse update(@PathVariable Long id,
			@Valid @RequestBody GoalRequest request,
			@AuthenticationPrincipal User user) {
		return savingsGoalService.update(id, request, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		savingsGoalService.delete(id, user);
	}
}
