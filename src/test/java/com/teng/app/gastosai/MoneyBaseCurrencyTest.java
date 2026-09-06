package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.v2.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Money#toBaseCentavos(BigDecimal, BigDecimal)} — the conversion the recurring and upcoming
 * v2 responses serve (TEN-360).
 */
class MoneyBaseCurrencyTest {

	@Test
	void convertsWithTheStoredRate() {
		assertThat(Money.toBaseCentavos(new BigDecimal("20.00"), new BigDecimal("58.7500")))
				.isEqualTo(117_500L);
	}

	@Test
	void rateOfOneLeavesTheAmountAlone() {
		assertThat(Money.toBaseCentavos(new BigDecimal("1500.00"), BigDecimal.ONE))
				.isEqualTo(Money.toCentavos(new BigDecimal("1500.00")));
	}

	@Test
	void absentRateMeansTheAmountIsAlreadyInBaseCurrency() {
		assertThat(Money.toBaseCentavos(new BigDecimal("42.50"), null)).isEqualTo(4_250L);
	}

	@Test
	void absentAmountStaysAbsentRatherThanBecomingZero() {
		assertThat(Money.toBaseCentavos(null, new BigDecimal("58.7500"))).isNull();
	}

	@Test
	void roundsToAWholeCentavoHalfUp() {
		// 3.33 x 1.5 = 4.995 -> 499.5 centavos, which HALF_UP takes to 500. No fractional minor
		// unit reaches the wire.
		assertThat(Money.toBaseCentavos(new BigDecimal("3.33"), new BigDecimal("1.5000")))
				.isEqualTo(500L);
	}

	@Test
	void matchesTheStoredColumnForTheSameAmountAndRate() {
		// What ExpenseService writes into amountInBaseCurrency: the product at NUMERIC(19,4), which
		// ExpenseResponseV2 then serves through toCentavos. Computing it in one call must not
		// disagree with computing it in those two steps.
		BigDecimal amount = new BigDecimal("19.99");
		BigDecimal rate = new BigDecimal("58.7513");
		BigDecimal stored = amount.multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP);

		assertThat(Money.toBaseCentavos(amount, rate)).isEqualTo(Money.toCentavos(stored));
	}
}
