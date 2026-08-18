package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AdminObservabilityController;
import com.teng.app.gastosai.dto.AppEventDto;
import com.teng.app.gastosai.dto.ObservabilityCost;
import com.teng.app.gastosai.dto.ObservabilityHealth;
import com.teng.app.gastosai.dto.ObservabilitySummary;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link AdminObservabilityController} on the v2 path. Access enforced in SecurityConfig
 * (`/admin/**`).
 *
 * <p>{@link ObservabilityCost} keeps its decimal {@code todayCostUsd} for the reason
 * {@link AdminAiUsageV2Controller} spells out: a USD inference cost is not a PHP amount.
 */
@RestController
@RequestMapping("/api/v2/admin/observability")
@RequiredArgsConstructor
public class AdminObservabilityV2Controller {

	private final AdminObservabilityController delegate;

	@GetMapping("/summary")
	@Operation(operationId = "v2AdminObservabilitySummary")
	public ObservabilitySummary summary() {
		return delegate.summary();
	}

	@GetMapping("/cost")
	@Operation(operationId = "v2AdminObservabilityCost")
	public ObservabilityCost cost() {
		return delegate.cost();
	}

	@GetMapping("/events")
	@Operation(operationId = "v2AdminObservabilityEvents")
	public List<AppEventDto> events(@RequestParam(required = false) String type,
			@RequestParam(defaultValue = "100") int limit) {
		return delegate.events(type, limit);
	}

	@GetMapping("/health")
	@Operation(operationId = "v2AdminObservabilityHealth")
	public ObservabilityHealth health() {
		return delegate.health();
	}
}
