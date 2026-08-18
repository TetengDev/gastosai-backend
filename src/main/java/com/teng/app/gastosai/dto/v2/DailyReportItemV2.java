package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.DailyReportItem;

/** {@link DailyReportItem} with {@code total} as integer centavos. */
public record DailyReportItemV2(String date, Long total) {

	public static DailyReportItemV2 from(DailyReportItem v1) {
		return new DailyReportItemV2(v1.date(), Money.toCentavos(v1.total()));
	}
}
