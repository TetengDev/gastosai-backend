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
import com.teng.app.gastosai.entity.WebhookEvent;
import com.teng.app.gastosai.exception.InvalidSignatureException;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PROVIDER = "paymongo";

    private final PaymentProvider paymentProvider;
    private final PaymentCheckoutRepository checkoutRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final PayMongoWebhookVerifier webhookVerifier;
    private final SubscriptionService subscriptionService;
    private final PricingProperties pricing;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

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

    /**
     * Applies one webhook delivery, at most once per provider event.
     *
     * <p>A provider retries until it sees a 2xx, so the same event arrives repeatedly and the
     * second arrival must change nothing. The key is the provider's own event id ({@code data.id}),
     * claimed in {@code webhook_event} inside the same transaction that applies the effect: a
     * delivery that fails rolls its claim back and stays retryable, a delivery that succeeds leaves
     * a claim no later copy can take. Two deliveries in flight at once both find no row, and the
     * unique constraint — not a read — decides which of them proceeds.
     *
     * <p>Deliberately not {@code @Transactional}: the losing delivery is recognised by the
     * constraint violation, and that can only be caught outside the transaction it aborted.
     */
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

        String eventId = root.path("data").path("id").asText();
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> claimAndApply(root, eventId));
        } catch (DataIntegrityViolationException e) {
            // Only a lost race for the event id is an already-processed delivery. Any other
            // integrity failure must still reach the caller as an error, so the provider retries
            // instead of being told a payment was handled that was not.
            if (eventId.isBlank() || !webhookEventRepository.existsByProviderAndEventId(PROVIDER, eventId)) {
                throw e;
            }
            log.info("paymongo_webhook_duplicate event_id={} — applied by a concurrent delivery", eventId);
        }
    }

    private void claimAndApply(JsonNode root, String eventId) {
        String eventType = root.path("data").path("attributes").path("type").asText();

        // A delivery with no event id cannot be deduplicated by id; it still goes through the
        // effect below, which is idempotent on the checkout's own status.
        if (!eventId.isBlank()) {
            if (webhookEventRepository.existsByProviderAndEventId(PROVIDER, eventId)) {
                log.info("paymongo_webhook_replay event_id={} — already applied, ignoring", eventId);
                return;
            }
            // Flushed here, before anything is activated: the claim is what a concurrent
            // delivery collides with, so it must reach the database first.
            webhookEventRepository.saveAndFlush(WebhookEvent.builder()
                    .provider(PROVIDER)
                    .eventId(eventId)
                    .eventType(eventType.isBlank() ? null : eventType)
                    .receivedAt(LocalDateTime.now())
                    .build());
        }

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
        subscriptionService.activate(checkout.getUser(), PlanKey.PREMIUM, PROVIDER, sessionId, periodEnd);

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
