package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.entity.GoalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@link GoalResponse} with {@code targetAmount} and {@code savedAmount} as integer centavos.
 *
 * <p>{@code progressPercent} stays decimal — a proportion, not an amount.
 */
public record GoalResponseV2(
		Long id,
		String name,
		Long targetAmount,
		Long savedAmount,
		BigDecimal progressPercent,
		LocalDate targetDate,
		GoalStatus status,
		boolean paused,
		LocalDateTime createdAt,
		String currency
) {

	public static GoalResponseV2 from(GoalResponse v1) {
		return new GoalResponseV2(
				v1.id(),
				v1.name(),
				Money.toCentavos(v1.targetAmount()),
				Money.toCentavos(v1.savedAmount()),
				v1.progressPercent(),
				v1.targetDate(),
				v1.status(),
				v1.paused(),
				v1.createdAt(),
				v1.currency());
	}
}
