package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

/**
 * An upcoming bill together with the exchange rate stored on the recurring expense it came from.
 *
 * <p>Internal to the service and controller layers — never serialized. It exists because
 * {@link UpcomingBillResponse} deliberately does not carry a rate and its published v1 shape is
 * frozen, while {@code /api/v2} has to serve {@code amountInBaseCurrency} computed from that rate.
 * Carrying the rate alongside the response keeps the conversion on the server without a second
 * query and without changing what v1 puts on the wire.
 */
public record UpcomingBillWithRate(
		UpcomingBillResponse bill,
		BigDecimal exchangeRate
) {
}
