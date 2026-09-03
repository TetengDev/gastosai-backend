package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ExpenseDisambiguateItem;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@link ExpenseDisambiguateItem} with {@code amount} as integer centavos.
 *
 * <p>Still not an {@link ExpenseChatItemV2}: this branch carries no {@code category}. The v1
 * distinction that its amount is unrounded disappears here — centavos are two places by
 * construction, so the value is rounded to the centavo on the way out, exactly as
 * {@link Money#toCentavos} does everywhere else on this surface.
 */
@Schema(description = "One expense the user must choose between before a delete can proceed, "
		+ "money in centavos.")
public record ExpenseDisambiguateItemV2(
		@Schema(description = "The expense's identifier. Send it back to name the one to delete.",
				example = "1204",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "What the expense was for — the field the user's ambiguous phrase "
				+ "matched.", example = "SM Supermarket",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String description,

		@Schema(description = "Amount in centavos, rounded from the stored four-place value.",
				example = "32000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,

		@Schema(description = "The expense date as `YYYY-MM-DD` in Asia/Manila, or an empty string "
				+ "on the rare rows with no date.", example = "2026-08-14",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String date
) {
}
