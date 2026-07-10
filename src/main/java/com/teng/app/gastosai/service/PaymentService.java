package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.PricingProperties;
import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.InvalidSignatureException;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentProvider paymentProvider;
    private final PaymentCheckoutRepository checkoutRepository;
    private final PayMongoWebhookVerifier webhookVerifier;
    private final SubscriptionService subscriptionService;
    private final PricingProperties pricing;
    private final ObjectMapper objectMapper;

    @Transactional
    public String startCheckout(User user, BillingPeriod period) {
        var session = paymentProvider.createCheckout(user, PlanKey.PREMIUM, period);
        var checkout = PaymentCheckout.builder()
                .user(user)
                .sessionId(session.sessionId())
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(period)
                .amountCentavos(period.centavos(pricing))
                .status(CheckoutStatus.PENDING)
                .build();
        checkoutRepository.save(checkout);
        return session.checkoutUrl();
    }

    @Transactional
    public void handleWebhook(String rawBody, String signatureHeader) {
        if (!webhookVerifier.verify(rawBody, signatureHeader)) {
            throw new InvalidSignatureException();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            // Malformed JSON from a signature-valid source is unexpected and not retryable — ack it.
            return;
        }

        String eventType = root.path("data").path("attributes").path("type").asText();
        if (!"checkout_session.payment.paid".equals(eventType)) {
            return;
        }

        String sessionId = root.path("data").path("attributes").path("data").path("id").asText();
        if (sessionId.isBlank()) {
            return;
        }

        PaymentCheckout checkout = checkoutRepository.findBySessionId(sessionId).orElse(null);
        if (checkout == null || checkout.getStatus() == CheckoutStatus.PAID) {
            return;
        }

        // Let any persistence failure below propagate as a 5xx so PayMongo retries the event
        // (wrapping it as IllegalStateException would surface as 400 and drop a paid event).
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = checkout.getBillingPeriod().plus(now);
        subscriptionService.activate(checkout.getUser(), PlanKey.PREMIUM, "paymongo", sessionId, periodEnd);

        checkout.setStatus(CheckoutStatus.PAID);
        checkout.setPaidAt(now);
        checkoutRepository.save(checkout);
    }

    @Transactional(readOnly = true)
    public CurrentSubscriptionInfo currentSubscription(User user) {
        var subscriptionOpt = subscriptionService.findCurrentSubscription(user);
        Optional<PaymentCheckout> paidCheckout = checkoutRepository
                .findFirstByUserAndStatusOrderByCreatedAtDesc(user, CheckoutStatus.PAID);

        if (subscriptionOpt.isEmpty()) {
            return new CurrentSubscriptionInfo(PlanKey.FREE, SubscriptionStatus.INACTIVE, null, null);
        }

        var sub = subscriptionOpt.get();
        BillingPeriod billingPeriod = paidCheckout.map(PaymentCheckout::getBillingPeriod).orElse(null);

        return new CurrentSubscriptionInfo(
                sub.getPlan().getPlanKey(),
                sub.getStatus(),
                sub.getCurrentPeriodEnd(),
                billingPeriod
        );
    }

    public record CurrentSubscriptionInfo(
            PlanKey plan,
            SubscriptionStatus status,
            LocalDateTime currentPeriodEnd,
            BillingPeriod billingPeriod
    ) {}
}
