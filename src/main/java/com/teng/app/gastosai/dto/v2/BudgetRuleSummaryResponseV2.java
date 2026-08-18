package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetRuleSummaryResponse;
import com.teng.app.gastosai.entity.Bucket;
import com.teng.app.gastosai.entity.BudgetRuleType;

import java.util.List;

/**
 * {@link BudgetRuleSummaryResponse} with the money fields as integer centavos.
 *
 * <p>{@code percentUsed} keeps its {@code double} type: it is a proportion, not money, so the
 * contract's no-floating-point rule does not reach it.
 */
public record BudgetRuleSummaryResponseV2(
		String month,
		BudgetRuleType ruleType,
		Long monthlyIncome,
		List<BucketSummaryV2> buckets,
		Long unassignedSpent
) {

	public record BucketSummaryV2(
			Bucket bucket,
			int percent,
			Long target,
			Long spent,
			Long remaining,
			double percentUsed
	) {

		public static BucketSummaryV2 from(BudgetRuleSummaryResponse.BucketSummary v1) {
			return new BucketSummaryV2(
					v1.bucket(),
					v1.percent(),
					Money.toCentavos(v1.target()),
					Money.toCentavos(v1.spent()),
					Money.toCentavos(v1.remaining()),
					v1.percentUsed());
		}
	}

	public static BudgetRuleSummaryResponseV2 from(BudgetRuleSummaryResponse v1) {
		return new BudgetRuleSummaryResponseV2(
				v1.month(),
				v1.ruleType(),
				Money.toCentavos(v1.monthlyIncome()),
				v1.buckets() == null ? null : v1.buckets().stream().map(BucketSummaryV2::from).toList(),
				Money.toCentavos(v1.unassignedSpent()));
	}
}
