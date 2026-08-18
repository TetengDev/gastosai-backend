package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.CategoryReportItem;

/** {@link CategoryReportItem} with {@code total} as integer centavos. */
public record CategoryReportItemV2(String category, Long total) {

	public static CategoryReportItemV2 from(CategoryReportItem v1) {
		return new CategoryReportItemV2(v1.category(), Money.toCentavos(v1.total()));
	}
}
