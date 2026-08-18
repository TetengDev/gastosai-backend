package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.GoalRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * {@link GoalRequest} with {@code targetAmount} and {@code savedAmount} as integer centavos.
 *
 * <p>{@code paused} stays a boxed {@code Boolean} for the reason {@link GoalRequest} documents: a
 * record has no field defaults, so an absent primitive would fail deserialization on a request the
 * contract marks optional.
 */
public record GoalRequestV2(
		@NotBlank String name,
		@NotNull @Min(1) Long targetAmount,
		@NotNull @Min(0) Long savedAmount,
		LocalDate targetDate,
		Boolean paused,
		String currency
) {

	public GoalRequest toV1() {
		return new GoalRequest(name, Money.toDecimal(targetAmount), Money.toDecimal(savedAmount),
				targetDate, paused, currency);
	}
}
