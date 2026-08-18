package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AdminAiUsageController;
import com.teng.app.gastosai.dto.AiUsageSummaryItem;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link AdminAiUsageController} on the v2 path.
 *
 * <p>{@code estimatedCostUsd} stays a decimal, deliberately. It is a USD inference cost, not a PHP
 * amount: it runs to six significant places below a cent, so rounding it to "centavos" would round
 * most rows to zero and destroy the number's only purpose. That is why {@code V23} gave the expense,
 * budget, goal and recurring tables centavos columns and left {@code ai_usage} alone — v2 follows
 * the same line the migration drew.
 */
@RestController
@RequestMapping("/api/v2/admin/ai-usage")
@RequiredArgsConstructor
public class AdminAiUsageV2Controller {

	private final AdminAiUsageController delegate;

	@GetMapping("/summary")
	@Operation(operationId = "v2AdminAiUsageSummary")
	public List<AiUsageSummaryItem> summary() {
		return delegate.summary();
	}
}
