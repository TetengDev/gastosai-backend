package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.entity.Frequency;

/** {@link UpcomingBillResponse} with {@code amount} as integer centavos. */
public record UpcomingBillResponseV2(
		Long id,
		String name,
		Long amount,
		String categoryName,
		Frequency frequency,
		String dueDate,
		String currency
) {

	public static UpcomingBillResponseV2 from(UpcomingBillResponse v1) {
		return new UpcomingBillResponseV2(
				v1.id(),
				v1.name(),
				Money.toCentavos(v1.amount()),
				v1.categoryName(),
				v1.frequency(),
				v1.dueDate(),
				v1.currency());
	}
}
