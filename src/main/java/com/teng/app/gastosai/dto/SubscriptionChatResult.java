package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The {@code result} of a {@code get_subscription} chat turn.
 *
 * <p>Distinct from {@link SubscriptionResponse} and {@link EntitlementResponse}: this is the
 * entitlement view the chat assistant reports — what the user may do right now — not the billing
 * record. Documentation only; the wire is unchanged.
 */
@Schema(description = "The user's plan and what it entitles them to, as reported by a chat turn.")
public record SubscriptionChatResult(
		@Schema(description = "The tier in force. `FREE` is the implicit default when there is no "
				+ "active subscription.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		PlanKey plan,

		@Schema(description = "The subscription's lifecycle state. Only `ACTIVE` and `TRIAL` grant a "
				+ "paid plan's features — the other values mean the effective plan is `FREE`.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		SubscriptionStatus status,

		@Schema(description = "The capabilities currently unlocked. Unordered, and already resolved "
				+ "against `status` — a client gates on membership here, never on `plan`.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<FeatureKey> features,

		@Schema(description = "True when the account is an administrator, which bypasses plan gating "
				+ "and AI quota entirely.", example = "false",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean admin
) {
}
