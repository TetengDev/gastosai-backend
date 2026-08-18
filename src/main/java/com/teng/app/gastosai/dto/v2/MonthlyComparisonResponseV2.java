package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.MonthlyComparisonResponse;

import java.math.BigDecimal;

/**
 * {@link MonthlyComparisonResponse} with the two totals as integer centavos.
 *
 * <p>{@code changePercent} stays decimal — it is derived from the totals, not a total itself.
 */
public record MonthlyComparisonResponseV2(
		String month,
		Long currentTotal,
		Long previousTotal,
		BigDecimal changePercent
) {

	public static MonthlyComparisonResponseV2 from(MonthlyComparisonResponse v1) {
		return new MonthlyComparisonResponseV2(
				v1.month(),
				Money.toCentavos(v1.currentTotal()),
				Money.toCentavos(v1.previousTotal()),
				v1.changePercent());
	}
}
