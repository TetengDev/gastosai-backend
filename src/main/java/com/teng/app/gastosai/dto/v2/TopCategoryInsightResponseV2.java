package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.TopCategoryInsightResponse;

import java.math.BigDecimal;

/** {@link TopCategoryInsightResponse} with {@code total} as integer centavos. */
public record TopCategoryInsightResponseV2(
		String month,
		String category,
		Long total,
		BigDecimal percentOfMonthTotal
) {

	public static TopCategoryInsightResponseV2 from(TopCategoryInsightResponse v1) {
		return new TopCategoryInsightResponseV2(
				v1.month(),
				v1.category(),
				Money.toCentavos(v1.total()),
				v1.percentOfMonthTotal());
	}
}
