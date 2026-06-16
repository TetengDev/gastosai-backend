package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;

import java.util.Set;

/** What the authenticated user is entitled to: their plan, subscription status, and granted features. */
public record EntitlementResponse(PlanKey plan, SubscriptionStatus status, Set<FeatureKey> features, boolean admin) {
}
