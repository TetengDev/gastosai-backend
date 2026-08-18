package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.BudgetRuleController;
import com.teng.app.gastosai.dto.BucketAssignmentRequest;
import com.teng.app.gastosai.dto.BudgetRuleEnabledRequest;
import com.teng.app.gastosai.dto.v2.BudgetRuleRequestV2;
import com.teng.app.gastosai.dto.v2.BudgetRuleResponseV2;
import com.teng.app.gastosai.dto.v2.BudgetRuleSummaryResponseV2;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@link BudgetRuleController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/budget-rules")
@RequiredArgsConstructor
public class BudgetRuleV2Controller {

	private final BudgetRuleController delegate;

	@GetMapping
	@Operation(operationId = "v2GetBudgetRule")
	public BudgetRuleResponseV2 get(@AuthenticationPrincipal User user) {
		return BudgetRuleResponseV2.from(delegate.get(user));
	}

	@PutMapping
	@Operation(operationId = "v2UpsertBudgetRule")
	public BudgetRuleResponseV2 upsert(@Valid @RequestBody BudgetRuleRequestV2 request,
			@AuthenticationPrincipal User user) {
		return BudgetRuleResponseV2.from(delegate.upsert(request.toV1(), user));
	}

	@PutMapping("/enabled")
	@Operation(operationId = "v2SetBudgetRuleEnabled")
	public BudgetRuleResponseV2 setEnabled(@RequestBody BudgetRuleEnabledRequest request,
			@AuthenticationPrincipal User user) {
		return BudgetRuleResponseV2.from(delegate.setEnabled(request, user));
	}

	@PutMapping("/buckets")
	@Operation(operationId = "v2AssignBuckets")
	public ResponseEntity<Void> assignBuckets(@Valid @RequestBody BucketAssignmentRequest request,
			@AuthenticationPrincipal User user) {
		return delegate.assignBuckets(request, user);
	}

	@GetMapping("/summary")
	@Operation(operationId = "v2BudgetRuleSummary")
	public BudgetRuleSummaryResponseV2 summary(
			@RequestParam(value = "month", required = false) String month,
			@AuthenticationPrincipal User user) {
		return BudgetRuleSummaryResponseV2.from(delegate.summary(month, user));
	}
}
