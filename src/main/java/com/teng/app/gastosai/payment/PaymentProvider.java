package com.teng.app.gastosai.payment;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;

public interface PaymentProvider {

    String key();

    PaymentCheckoutSession createCheckout(User user, PlanKey plan, BillingPeriod period);

    record PaymentCheckoutSession(String sessionId, String checkoutUrl) {}
}
