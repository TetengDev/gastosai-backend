package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.controller.AiInsightController;
import com.teng.app.gastosai.dto.MonthSummaryInsightResponse;
import com.teng.app.gastosai.dto.RecommendationsInsightResponse;
import com.teng.app.gastosai.dto.v2.TopCategoryInsightResponseV2;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@link AiInsightController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/ai/insights")
@RequiredArgsConstructor
public class AiInsightV2Controller {

	private final AiInsightController delegate;

	@GetMapping("/top-category")
	@RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
	@Operation(operationId = "v2TopCategoryInsight")
	public TopCategoryInsightResponseV2 topCategory(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return TopCategoryInsightResponseV2.from(delegate.topCategory(month, user));
	}

	/**
	 * Unchanged shape: the summary is prose the assistant wrote, already formatted for a reader.
	 * There is no numeric money field for a client to round.
	 */
	@GetMapping("/month-summary")
	@RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
	@Operation(operationId = "v2MonthSummaryInsight")
	public MonthSummaryInsightResponse monthSummary(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return delegate.monthSummary(month, user);
	}

	/** Unchanged shape, for the reason {@link #monthSummary} gives. */
	@GetMapping("/recommendations")
	@RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
	@Operation(operationId = "v2RecommendationsInsight")
	public RecommendationsInsightResponse recommendations(@RequestParam String month,
			@AuthenticationPrincipal User user) {
		return delegate.recommendations(month, user);
	}
}
