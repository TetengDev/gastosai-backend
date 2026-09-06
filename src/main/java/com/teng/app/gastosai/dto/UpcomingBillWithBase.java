package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

/**
 * An upcoming bill together with its amount converted to the base currency.
 *
 * <p>Internal to the service and controller layers — never serialized. It exists because
 * {@link UpcomingBillResponse} carries neither a converted amount nor the rate to derive one, and
 * its published v1 shape is frozen, while {@code /api/v2} has to serve
 * {@code amountInBaseCurrency}.
 *
 * <p>The converted amount is computed from the recurring expense's stored amount, at the full
 * {@code NUMERIC(19,4)} precision the column holds — not from the two-place amount the v1 response
 * displays. Converting from the rounded value would put this endpoint a centavo away from the
 * stored {@code amountInBaseCurrency} of an expense with the same amount and rate.
 */
public record UpcomingBillWithBase(
		UpcomingBillResponse bill,
		BigDecimal amountInBaseCurrency
) {
}
