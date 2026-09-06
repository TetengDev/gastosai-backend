package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.dto.UpcomingBillWithBase;
import com.teng.app.gastosai.entity.Frequency;

/**
 * {@link UpcomingBillResponse} with {@code amount} as integer centavos.
 *
 * <p>{@code amountInBaseCurrency} is the amount converted with the exchange rate stored on the
 * recurring expense the bill was projected from, computed by the service from that expense's
 * stored amount. The v1 response carries no rate at all, so a client had no way to show a non-PHP
 * bill in pesos: it could only print the raw minor units of the bill's own currency behind a peso
 * sign, which reads a $20.00 bill as ₱20.00.
 */
public record UpcomingBillResponseV2(
		Long id,
		String name,
		Long amount,
		String categoryName,
		Frequency frequency,
		String dueDate,
		String currency,
		Long amountInBaseCurrency
) {

	public static UpcomingBillResponseV2 from(UpcomingBillWithBase withBase) {
		UpcomingBillResponse v1 = withBase.bill();
		return new UpcomingBillResponseV2(
				v1.id(),
				v1.name(),
				Money.toCentavos(v1.amount()),
				v1.categoryName(),
				v1.frequency(),
				v1.dueDate(),
				v1.currency(),
				Money.toCentavos(withBase.amountInBaseCurrency()));
	}
}
