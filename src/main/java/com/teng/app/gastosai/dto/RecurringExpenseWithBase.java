package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;

/**
 * A recurring expense together with its amount converted to the base currency.
 *
 * <p>Internal to the service, controller and chat layers, and the sibling of
 * {@link UpcomingBillWithBase}. {@link RecurringExpenseResponse} publishes the rate but rounds the
 * amount to two places for display, so converting from that response would drop the third and
 * fourth decimal the {@code NUMERIC(19,4)} column can hold. The conversion is done from the stored
 * amount instead, the way {@code ExpenseService} computes the value it stores.
 *
 * <p>{@code @JsonValue} is what lets a chat turn carry this record as its {@code result} without
 * moving the v1 wire: the pair serializes as the response alone, exactly as it did when
 * {@code ChatActionService} returned a bare {@link RecurringExpenseResponse}, while
 * {@code ChatResultV2} still gets the converted amount that only the entity could supply. The
 * converted amount itself never reaches a client except as the {@code amountInBaseCurrency} of a
 * v2 response.
 */
public record RecurringExpenseWithBase(
		@JsonValue RecurringExpenseResponse response,
		BigDecimal amountInBaseCurrency
) {
}
