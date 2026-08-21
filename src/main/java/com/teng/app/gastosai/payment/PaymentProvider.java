package com.teng.app.gastosai.payment;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;

import java.time.Instant;
import java.util.Optional;

public interface PaymentProvider {

    String key();

    PaymentCheckoutSession createCheckout(User user, PlanKey plan, BillingPeriod period);

    /**
     * What the provider itself says about a checkout session, asked directly rather than waited for.
     *
     * <p>A webhook is the only thing that turns a payment into a subscription, so a delivery that
     * never arrives costs a paying customer their access in silence. This is the read that makes
     * that state visible: the provider is the authority on whether money moved, and reconciliation
     * compares its answer against what was activated here.
     *
     * @return empty when the provider has no such session; never empty merely because it was unpaid
     */
    Optional<RemoteCheckout> fetchCheckout(String sessionId);

    record PaymentCheckoutSession(String sessionId, String checkoutUrl) {}

    /**
     * The provider's own verdict on one session. {@code paymentId} and {@code paidAt} are populated
     * only when {@code paid} — they are the identifiers an operator needs to settle a discrepancy
     * against the provider's dashboard by hand.
     */
    record RemoteCheckout(String sessionId, boolean paid, int amountCentavos, String paymentId, Instant paidAt) {}
}
