package com.teng.app.gastosai.payment;

import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;

/**
 * Extension point for payment integrations (Stripe, PayMongo, GCash, …). The app never depends on a
 * concrete provider: subscription state lives in {@code user_subscriptions} and is mutated through
 * {@link com.teng.app.gastosai.service.SubscriptionService}. A future provider implements this
 * interface, starts a checkout, and calls {@code SubscriptionService.activate(...)} from its webhook.
 * No implementation ships yet — this only fixes the seam so adding one is additive.
 */
public interface PaymentProvider {

    /** Stable identifier persisted on the subscription (e.g. "stripe", "paymongo"). */
    String key();

    /** Begins a hosted checkout for the given user and target plan; returns a redirect URL. */
    String createCheckoutUrl(User user, PlanKey targetPlan);
}
