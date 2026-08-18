package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.MonthlyReportItem;

/**
 * {@link MonthlyReportItem} with {@code total} as integer centavos.
 *
 * <p>The aggregate is still summed in the reporting JPQL over the decimal column and rounded once,
 * here, at the edge. Summing centavos independently would be a second aggregate over the same rows
 * — and the first place the two surfaces could disagree.
 */
public record MonthlyReportItemV2(String month, Long total) {

	public static MonthlyReportItemV2 from(MonthlyReportItem v1) {
		return new MonthlyReportItemV2(v1.month(), Money.toCentavos(v1.total()));
	}
}
