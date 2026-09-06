package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

/**
 * A recurring expense together with its amount converted to the base currency.
 *
 * <p>Internal to the service and controller layers — never serialized, and the sibling of
 * {@link UpcomingBillWithBase}. {@link RecurringExpenseResponse} publishes the rate but rounds the
 * amount to two places for display, so converting from that response would drop the third and
 * fourth decimal the {@code NUMERIC(19,4)} column can hold. The conversion is done from the stored
 * amount instead, the way {@code ExpenseService} computes the value it stores.
 */
public record RecurringExpenseWithBase(
		RecurringExpenseResponse response,
		BigDecimal amountInBaseCurrency
) {

	/**
	 * Recovers the converted amount from the rate the response publishes, for the one caller that
	 * has a {@link RecurringExpenseResponse} and no entity behind it: the chat turn that echoes a
	 * row it was handed by the service.
	 *
	 * <p>Exact whenever the stored amount has at most two decimals, which is every amount a client
	 * can type. Every path that can reach the entity converts from the stored amount instead, so
	 * this is a fallback and not the rule.
	 */
	public static RecurringExpenseWithBase fromPublishedRate(RecurringExpenseResponse response) {
		BigDecimal rate = response.exchangeRate() != null ? response.exchangeRate() : BigDecimal.ONE;
		BigDecimal base = response.amount() == null
				? null
				: response.amount().multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP);
		return new RecurringExpenseWithBase(response, base);
	}
}
