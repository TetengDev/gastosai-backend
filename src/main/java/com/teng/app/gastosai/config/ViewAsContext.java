package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.PlanKey;

/**
 * Per-request admin "View As" overrides, populated by {@link ViewAsInterceptor} only when the
 * authenticated user is an admin. Lets an admin preview the app as another plan tier or with AI
 * disabled, without changing real data. Read by EntitlementService and the AI key gate.
 */
public final class ViewAsContext {

	private ViewAsContext() {
	}

	private record Overrides(PlanKey plan, Boolean aiEnabled) {
	}

	private static final ThreadLocal<Overrides> HOLDER = new ThreadLocal<>();

	public static void set(PlanKey plan, Boolean aiEnabled) {
		HOLDER.set(new Overrides(plan, aiEnabled));
	}

	public static PlanKey plan() {
		Overrides o = HOLDER.get();
		return o == null ? null : o.plan();
	}

	/** TRUE/FALSE to force AI on/off, or null when not overridden. */
	public static Boolean aiEnabled() {
		Overrides o = HOLDER.get();
		return o == null ? null : o.aiEnabled();
	}

	public static void clear() {
		HOLDER.remove();
	}
}
