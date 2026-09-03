package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.CategoryTotalChatItem;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@link CategoryTotalChatItem} with {@code total} as integer centavos.
 *
 * <p>The item of a {@code get_category_totals} turn, and of
 * {@link MonthlyReportChatResultV2#categoryBreakdown()}.
 */
@Schema(description = "A category and what was spent in it over the requested window, in centavos.")
public record CategoryTotalChatItemV2(
		@Schema(description = "Category name, or `Uncategorized`.", example = "Groceries",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String category,

		@Schema(description = "Total spent in this category, in centavos.", example = "642050",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long total
) {
}
