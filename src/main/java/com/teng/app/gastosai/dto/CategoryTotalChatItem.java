package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One category's spend total. The {@code result} of a {@code get_category_totals} turn is a JSON
 * array of these, and {@link MonthlyReportChatResult#categoryBreakdown()} reuses the shape.
 *
 * <p>Documentation only — the wire is unchanged.
 */
@Schema(description = "A category and what was spent in it over the requested window.")
public record CategoryTotalChatItem(
		@Schema(description = "Category name, or `Uncategorized`.", example = "Groceries",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String category,

		@Schema(description = "Total spent in this category, in PHP, rounded to two decimal places.",
				example = "6420.50",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal total
) {
}
