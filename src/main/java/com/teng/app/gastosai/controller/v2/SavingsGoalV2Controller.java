package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.SavingsGoalController;
import com.teng.app.gastosai.dto.v2.GoalRequestV2;
import com.teng.app.gastosai.dto.v2.GoalResponseV2;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
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

/** {@link SavingsGoalController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/goals")
@RequiredArgsConstructor
public class SavingsGoalV2Controller {

	private final SavingsGoalController delegate;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateGoal")
	public GoalResponseV2 create(@Valid @RequestBody GoalRequestV2 request,
			@RequestParam(name = "force", defaultValue = "false") boolean force,
			@AuthenticationPrincipal User user) {
		return GoalResponseV2.from(delegate.create(request.toV1(), force, user));
	}

	@GetMapping
	@Operation(operationId = "v2ListGoals")
	public List<GoalResponseV2> list(@AuthenticationPrincipal User user) {
		return delegate.list(user).stream().map(GoalResponseV2::from).toList();
	}

	@GetMapping("/{id}")
	@Operation(operationId = "v2GetGoal")
	public GoalResponseV2 get(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return GoalResponseV2.from(delegate.get(id, user));
	}

	@PutMapping("/{id}")
	@Operation(operationId = "v2UpdateGoal")
	public GoalResponseV2 update(@PathVariable Long id,
			@Valid @RequestBody GoalRequestV2 request,
			@AuthenticationPrincipal User user) {
		return GoalResponseV2.from(delegate.update(id, request.toV1(), user));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteGoal")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}
}
