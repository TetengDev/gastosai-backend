package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.BudgetController;
import com.teng.app.gastosai.dto.v2.BudgetRequestV2;
import com.teng.app.gastosai.dto.v2.BudgetResponseV2;
import com.teng.app.gastosai.dto.v2.BudgetSummaryResponseV2;
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

/** {@link BudgetController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/budgets")
@RequiredArgsConstructor
public class BudgetV2Controller {

	private final BudgetController delegate;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateBudget")
	public BudgetResponseV2 create(@Valid @RequestBody BudgetRequestV2 request,
			@RequestParam(defaultValue = "false") boolean force,
			@AuthenticationPrincipal User user) {
		return BudgetResponseV2.from(delegate.create(request.toV1(), force, user));
	}

	@GetMapping
	@Operation(operationId = "v2ListBudgets")
	public List<BudgetResponseV2> list(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return delegate.list(month, user).stream().map(BudgetResponseV2::from).toList();
	}

	@PutMapping("/{id}")
	@Operation(operationId = "v2UpdateBudget")
	public BudgetResponseV2 update(@PathVariable Long id,
			@Valid @RequestBody BudgetRequestV2 request,
			@AuthenticationPrincipal User user) {
		return BudgetResponseV2.from(delegate.update(id, request.toV1(), user));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteBudget")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteAllBudgets")
	public void deleteAll(@RequestParam String month, @AuthenticationPrincipal User user) {
		delegate.deleteAll(month, user);
	}

	@GetMapping("/summary")
	@Operation(operationId = "v2BudgetSummary")
	public BudgetSummaryResponseV2 summary(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return BudgetSummaryResponseV2.from(delegate.summary(month, user));
	}
}
