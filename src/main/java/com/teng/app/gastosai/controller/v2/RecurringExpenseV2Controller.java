package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.RecurringExpenseController;
import com.teng.app.gastosai.dto.v2.RecurringExpenseRequestV2;
import com.teng.app.gastosai.dto.v2.RecurringExpenseResponseV2;
import com.teng.app.gastosai.dto.v2.UpcomingBillResponseV2;
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

/** {@link RecurringExpenseController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/recurring")
@RequiredArgsConstructor
public class RecurringExpenseV2Controller {

	private final RecurringExpenseController delegate;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateRecurringExpense")
	public RecurringExpenseResponseV2 create(@Valid @RequestBody RecurringExpenseRequestV2 request,
			@RequestParam(name = "force", defaultValue = "false") boolean force,
			@AuthenticationPrincipal User user) {
		return RecurringExpenseResponseV2.from(delegate.create(request.toV1(), force, user));
	}

	@GetMapping
	@Operation(operationId = "v2ListRecurringExpenses")
	public List<RecurringExpenseResponseV2> findAll(@AuthenticationPrincipal User user) {
		return delegate.findAll(user).stream().map(RecurringExpenseResponseV2::from).toList();
	}

	@PutMapping("/{id}")
	@Operation(operationId = "v2UpdateRecurringExpense")
	public RecurringExpenseResponseV2 update(@PathVariable Long id,
			@Valid @RequestBody RecurringExpenseRequestV2 request,
			@AuthenticationPrincipal User user) {
		return RecurringExpenseResponseV2.from(delegate.update(id, request.toV1(), user));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteRecurringExpense")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteAllRecurringExpenses")
	public void deleteAll(@AuthenticationPrincipal User user) {
		delegate.deleteAll(user);
	}

	@GetMapping("/upcoming")
	@Operation(operationId = "v2UpcomingBills")
	public List<UpcomingBillResponseV2> getUpcoming(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return delegate.getUpcoming(month, user).stream().map(UpcomingBillResponseV2::from).toList();
	}
}
