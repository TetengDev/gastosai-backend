package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.dto.UpcomingBillWithBase;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.RecurringExpenseService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
@RequestMapping("/recurring")
@RequiredArgsConstructor
public class RecurringExpenseController {

	private final RecurringExpenseService recurringExpenseService;

	/**
	 * The {@code {month}} path segment as the contract publishes it: {@code YYYY-MM} with a real
	 * month. The service enforces the same rule, so a client that skips the check still gets a 400.
	 */
	public static final String MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RecurringExpenseResponse create(@Valid @RequestBody RecurringExpenseRequest request,
			@RequestParam(name = "force", defaultValue = "false") boolean force,
			@AuthenticationPrincipal User user) {
		return createWithBase(request, force, user).response();
	}

	/** {@link #create} with the converted amount attached — see {@link #getUpcomingWithBase}. */
	public RecurringExpenseWithBase createWithBase(RecurringExpenseRequest request, boolean force,
			User user) {
		return recurringExpenseService.createWithBase(request, user, force);
	}

	@GetMapping
	public List<RecurringExpenseResponse> findAll(@AuthenticationPrincipal User user) {
		return recurringExpenseService.findAll(user);
	}

	/** {@link #findAll} with each converted amount attached — see {@link #getUpcomingWithBase}. */
	public List<RecurringExpenseWithBase> findAllWithBase(User user) {
		return recurringExpenseService.findAllWithBase(user);
	}

	@PutMapping("/{id}")
	public RecurringExpenseResponse update(@PathVariable Long id,
			@Valid @RequestBody RecurringExpenseRequest request,
			@AuthenticationPrincipal User user) {
		return updateWithBase(id, request, user).response();
	}

	/** {@link #update} with the converted amount attached — see {@link #getUpcomingWithBase}. */
	public RecurringExpenseWithBase updateWithBase(Long id, RecurringExpenseRequest request,
			User user) {
		return recurringExpenseService.updateWithBase(id, request, user);
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

	/**
	 * Marks the bill paid for {@code month} ({@code YYYY-MM}). Idempotent: marking a paid month
	 * again is a 204 too. Another user's bill is a 404; a malformed month is a 400.
	 */
	@PutMapping("/{id}/payments/{month}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markPaid(@PathVariable Long id,
			@Parameter(schema = @Schema(type = "string", pattern = MONTH_PATTERN)) @PathVariable String month,
			@AuthenticationPrincipal User user) {
		recurringExpenseService.markPaid(id, month, user);
	}

	/** Undoes {@link #markPaid}. Idempotent: undoing an unpaid month is a 204. */
	@DeleteMapping("/{id}/payments/{month}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unmarkPaid(@PathVariable Long id,
			@Parameter(schema = @Schema(type = "string", pattern = MONTH_PATTERN)) @PathVariable String month,
			@AuthenticationPrincipal User user) {
		recurringExpenseService.unmarkPaid(id, month, user);
	}

	/**
	 * Declares {@code List<UpcomingBillResponse>} rather than {@code ResponseEntity<?>}.
	 *
	 * The wildcard erased the payload type, so springdoc could only describe this response as a
	 * bare {@code object} — leaving the published contract unable to tell a client what comes
	 * back, and a generated client with nothing to generate. The JSON on the wire is unchanged;
	 * only the spec gains the shape it always had.
	 *
	 * The bad-month case now throws, which the global handler renders as a 400 ProblemDetail —
	 * the same shape every other validation failure returns, instead of the bare string this
	 * previously produced.
	 */
	@GetMapping("/upcoming")
	public List<UpcomingBillResponse> getUpcoming(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return getUpcomingWithBase(month, user).stream()
				.map(UpcomingBillWithBase::bill)
				.toList();
	}

	/**
	 * The same bills with the converted amount attached, for the v2 controller that delegates here.
	 *
	 * <p>Deliberately not a request mapping — this and its three siblings above are not endpoints
	 * and do not appear in the published contract. They exist so v2 can serve
	 * {@code amountInBaseCurrency} computed from the stored amount, while still going through this
	 * method's month validation, and while v1's response shape stays exactly what it has always
	 * been.
	 */
	public List<UpcomingBillWithBase> getUpcomingWithBase(String month, User user) {
		if (!month.matches("\\d{4}-\\d{2}")) {
			throw new IllegalArgumentException("Invalid month format. Expected YYYY-MM.");
		}
		return recurringExpenseService.getUpcomingWithBase(month, user);
	}
}
