package com.teng.app.gastosai.dto.v2;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place {@code /api/v2} converts between the decimal amounts the domain works in and the
 * integer centavos the v2 contract puts on the wire.
 *
 * <p>The rows are unchanged: {@code /api/v1} and {@code /api/v2} read the same
 * {@code NUMERIC(19,4)} columns through the same services, and this class only reshapes the value
 * at the serialization edge. That is what keeps the two surfaces from becoming two sources of
 * truth — v2 is a representation of v1's data, not a second copy of it.
 *
 * <p><strong>Rounding is HALF_UP</strong>, and that is not a free choice. {@code V24} derives
 * every {@code *_centavos} column with PostgreSQL's {@code round(numeric)}, which rounds a tie away
 * from zero; the application applies {@code RoundingMode.HALF_UP} everywhere it already reduces
 * money to two places. A row holding {@code 10.1250} therefore reads {@code 1013} here and
 * {@code 1013} in {@code expenses.amount_centavos} — one rule, stated once. Diverging from it would
 * make the API and the column that shadows it disagree by a centavo on exactly the rows nobody
 * checks.
 */
public final class Money {

	/**
	 * The largest amount v2 accepts, in centavos: the exact centavo equivalent of the
	 * {@code @Digits(integer = 15, fraction = 4)} ceiling v1 puts on an expense amount and a budget
	 * limit. Carried over rather than dropped so a request v1 rejects is not one v2 waves through.
	 */
	public static final long MAX_CENTAVOS = 99_999_999_999_999_999L;

	private Money() {
	}

	/**
	 * The decimal amount as an integer number of centavos, or {@code null} if it is absent.
	 *
	 * <p>Null is propagated rather than defaulted to zero: an absent amount and a zero amount are
	 * different answers, and collapsing them would invent data the v1 response did not carry.
	 */
	public static Long toCentavos(BigDecimal amount) {
		return amount == null ? null : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	/**
	 * An inbound centavo amount as the decimal the domain and the {@code NUMERIC(19,4)} columns
	 * expect, or {@code null} if it is absent.
	 *
	 * <p>Exact in both directions — a centavo value has an exact two-place decimal, so a v2 request
	 * reaches the service layer as the same number a v1 request carrying the equivalent decimal
	 * would have.
	 */
	public static BigDecimal toDecimal(Long centavos) {
		return centavos == null ? null : BigDecimal.valueOf(centavos, 2);
	}
}
