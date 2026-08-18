package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ParsedExpenseResult;

import java.time.LocalDateTime;

/**
 * {@link ParsedExpenseResult} with {@code amount} as integer centavos.
 *
 * <p>A parse result is what the client hands straight back to {@code POST /api/v2/expenses}, so it
 * has to speak the same money as {@link ExpenseRequestV2} — a decimal here would put a rounding
 * step back into the client, which is the thing this contract version removes.
 */
public record ParsedExpenseResultV2(
		Long amount,
		String category,
		LocalDateTime date,
		String description,
		String confidence,
		boolean saveable,
		String hint,
		String rejectionMessage
) {

	public static ParsedExpenseResultV2 from(ParsedExpenseResult v1) {
		return new ParsedExpenseResultV2(
				Money.toCentavos(v1.amount()),
				v1.category(),
				v1.date(),
				v1.description(),
				v1.confidence(),
				v1.saveable(),
				v1.hint(),
				v1.rejectionMessage());
	}
}
