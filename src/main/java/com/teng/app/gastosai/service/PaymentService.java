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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService implements ApplicationRunner {

    private static final String PROVIDER = "paymongo";
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    /** The provider event that says money moved for a checkout session. */
    private static final String PAID_EVENT = "checkout_session.payment.paid";

    /**
     * The provider events that say a payment was attempted and refused.
     *
     * <p>Both names are accepted because only one of them carries the checkout session in the field
     * this service reads ({@code data.attributes.data.id}). A payment-scoped {@code payment.failed}
     * names a payment instead, so its id matches no checkout here and the delivery is acknowledged
     * and ignored — the checkout it belonged to then resolves as {@code EXPIRED} when its window
     * runs out, which is the whole reason expiry does not depend on an event arriving.
     */
    private static final List<String> FAILED_EVENTS =
            List.of("checkout_session.payment.failed", "payment.failed");

    /**
     * How many of a user's abandoned checkouts one {@code startCheckout} will resolve. A user with
     * more stale rows than this keeps the remainder until their next attempt; the bound is here so
     * that a sweep which somehow fails to advance ends anyway instead of looping against the
     * database forever.
     */
    private static final int MAX_EXPIRY_SWEEP = 50;

    /** The command-line option {@code scripts/reconcile-payments.sh} passes; see {@link #run}. */
    public static final String RECONCILE_OPTION = "reconcile-payments";

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
        expireAbandonedCheckouts(user, LocalDateTime.now());

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
     * Resolves this user's abandoned checkouts before a new one is created.
     *
     * <p>Starting a checkout is the moment the stale ones matter and the moment their owner is
     * known, so the sweep is scoped to that user rather than run as a background job over the whole
     * table. Nothing here blocks the new checkout either way — a leftover {@code PENDING} row never
     * prevented one — but leaving a user with a growing pile of rows that claim to be in progress is
     * how "am I already paying for this?" becomes unanswerable.
     *
     * <p>It walks {@code findFirstByUserAndStatusOrderByCreatedAtDesc}, the only pending-scoped
     * query {@code PaymentCheckoutRepository} offers and — that repository being outside this
     * issue's ownership — the only one available. Newest first means the loop stops at the first
     * checkout still inside its window, which can leave older elapsed rows for the next attempt.
     * That is acceptable: {@link PaymentCheckout#effectiveStatus} already reads those rows as
     * expired, so the stored status is catching up with a decision the clock has made, not making
     * it. A {@code findByUserAndStatus} here would resolve them in one pass and is the first thing
     * to reach for if that repository ever opens up.
     */
    private void expireAbandonedCheckouts(User user, LocalDateTime now) {
        for (int swept = 0; swept < MAX_EXPIRY_SWEEP; swept++) {
            Optional<PaymentCheckout> pending = checkoutRepository
                    .findFirstByUserAndStatusOrderByCreatedAtDesc(user, CheckoutStatus.PENDING);
            if (pending.isEmpty() || !pending.get().expireIfElapsed(now)) {
                return;
            }
            checkoutRepository.save(pending.get());
            log.info("checkout_expired session_id={} created_at={} — abandoned past the {}h window",
                    pending.get().getSessionId(), pending.get().getCreatedAt(), PaymentCheckout.WINDOW.toHours());
        }
        log.warn("checkout_expiry_sweep_truncated user_id={} — more than {} abandoned checkouts",
                user.getId(), MAX_EXPIRY_SWEEP);
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
        } catch (DataAccessException e) {
            // Only a lost race for the event id is an already-processed delivery. Any other
            // persistence failure must reach the provider as a retryable status, so the delivery
            // comes back instead of the payment being dropped.
            if (e instanceof DataIntegrityViolationException && alreadyClaimed(eventId)) {
                log.info("paymongo_webhook_duplicate event_id={} — applied by a concurrent delivery", eventId);
                return;
            }
            throw retryable(eventId, e);
        }
    }

    /**
     * Whether the event id is already claimed in {@code webhook_event} — i.e. the integrity failure
     * just caught was a concurrent delivery of this same event. A lookup that itself fails answers
     * {@code false}: the delivery is then treated as retryable, which is the safe way to be wrong.
     */
    private boolean alreadyClaimed(String eventId) {
        if (eventId.isBlank()) {
            return false;
        }
        try {
            return webhookEventRepository.existsByProviderAndEventId(PROVIDER, eventId);
        } catch (DataAccessException e) {
            log.warn("paymongo_webhook_claim_lookup_failed event_id={}: {}", eventId, e.getMessage());
            return false;
        }
    }

    /**
     * A persistence failure while applying a verified webhook is answered 503, not the 400 that
     * {@code GlobalExceptionHandler} gives a {@link DataAccessException} on an ordinary request.
     * PayMongo does not retry a 4xx, so a 400 here silently drops a paid event; a 5xx is the only
     * answer that gets the delivery back. The live case is the {@code (user_id, provider_ref)}
     * unique constraint on {@code user_subscriptions}.
     */
    private ResponseStatusException retryable(String eventId, DataAccessException cause) {
        log.error("paymongo_webhook_failed event_id={} — answering 503 so the provider retries: {}",
                eventId, cause.getMessage(), cause);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Webhook could not be applied; retry the delivery.", cause);
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

        boolean paid = PAID_EVENT.equals(eventType);
        if (!paid && !FAILED_EVENTS.contains(eventType)) {
            return;
        }

        String sessionId = root.path("data").path("attributes").path("data").path("id").asText();
        if (sessionId.isBlank()) {
            return;
        }

        PaymentCheckout checkout = checkoutRepository.findBySessionId(sessionId).orElse(null);
        if (checkout == null) {
            return;
        }

        if (paid) {
            applyPaid(checkout, sessionId);
        } else {
            applyFailed(checkout, sessionId);
        }
    }

    /**
     * Activates the subscription this checkout was for and marks it paid.
     *
     * <p>Applied even when the checkout had already been given up on as expired or failed: this
     * event is the provider saying it took the money, and that outranks anything this service
     * concluded from silence. Only an already-{@code PAID} checkout is skipped, which is the replay
     * case.
     */
    private void applyPaid(PaymentCheckout checkout, String sessionId) {
        if (checkout.getStatus() == CheckoutStatus.PAID) {
            return;
        }
        if (checkout.getStatus().isResolved()) {
            log.info("paymongo_payment_paid_after_resolution session_id={} previous_status={} — "
                    + "activating anyway, the provider settled it", sessionId, checkout.getStatus());
        }

        // Let any persistence failure below propagate as a 5xx so PayMongo retries the event
        // (wrapping it as IllegalStateException would surface as 400 and drop a paid event).
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = checkout.getBillingPeriod().plus(now);
        subscriptionService.activate(checkout.getUser(), PlanKey.PREMIUM, PROVIDER, sessionId, periodEnd);

        checkout.markPaid(now);
        checkoutRepository.save(checkout);
    }

    /**
     * Marks a checkout the provider refused, so it reads as a declined payment rather than as an
     * abandoned page. Nothing is activated and nothing is revoked — a failed attempt on a checkout
     * that had already succeeded, or one already resolved, changes nothing.
     */
    private void applyFailed(PaymentCheckout checkout, String sessionId) {
        if (!checkout.markFailed()) {
            log.info("paymongo_payment_failed_ignored session_id={} status={} — already resolved",
                    sessionId, checkout.getStatus());
            return;
        }
        checkoutRepository.save(checkout);
        log.info("paymongo_payment_failed session_id={} — checkout marked FAILED", sessionId);
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

    // ---------------------------------------------------------------------------------------
    // Reconciliation (TEN-150)
    // ---------------------------------------------------------------------------------------

    /**
     * Finds payments the provider accepted that never became a subscription here.
     *
     * <p>Everything above assumes the webhook arrives. When it does not — a delivery lost in
     * transit, or one this service answered 503 and the provider gave up retrying — the customer
     * has paid and has no access, and nothing in the system says so. This is the check that says
     * so: for every checkout not marked paid locally, PayMongo is asked directly whether it settled
     * that session, and a "yes" is a discrepancy.
     *
     * <p><strong>It writes nothing.</strong> Repairing a gap is deliberately left to a human, so
     * the check is safe to run at any time and as often as wanted: two runs against the same
     * database report the same rows and change neither. Read-only is also what makes it usable
     * against production while a payment is being investigated.
     *
     * <p>A provider lookup that fails is recorded in {@link ReconciliationReport#unresolved()}
     * rather than aborting the run — one unreachable session must not hide the rest — and is never
     * silently counted as "not paid".
     *
     * <p>The scan reads through {@link PaymentCheckoutRepository#findAll()} and filters here rather
     * than querying by status: {@code PaymentCheckoutRepository} is outside this issue's ownership,
     * and the checkout table is small and this runs on demand. A status-scoped query belongs here
     * the moment either stops being true.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcileActivations() {
        List<PaymentCheckout> checkouts = checkoutRepository.findAll();
        List<ActivationGap> gaps = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int providerQueries = 0;
        LocalDateTime now = LocalDateTime.now();

        for (PaymentCheckout checkout : checkouts) {
            if (checkout.getStatus() == CheckoutStatus.PAID) {
                // Already agrees with the provider on the payment, so no lookup is spent on it.
                // What can still be missing is the activation the payment was for.
                if (subscriptionService.findCurrentSubscription(checkout.getUser()).isEmpty()) {
                    gaps.add(gap(GapKind.PAID_WITHOUT_SUBSCRIPTION, checkout, null, now));
                }
                continue;
            }

            providerQueries++;
            Optional<PaymentProvider.RemoteCheckout> remote;
            try {
                remote = paymentProvider.fetchCheckout(checkout.getSessionId());
            } catch (RuntimeException e) {
                log.warn("payment_reconciliation_lookup_failed session_id={}: {}",
                        checkout.getSessionId(), e.getMessage());
                unresolved.add("session=%s user=%d lookup_failed=%s"
                        .formatted(checkout.getSessionId(), userIdOf(checkout), e.getMessage()));
                continue;
            }

            if (remote.isPresent() && remote.get().paid()) {
                gaps.add(gap(GapKind.PROVIDER_PAID_LOCALLY_UNPAID, checkout, remote.get(), now));
            }
        }

        return new ReconciliationReport(checkouts.size(), providerQueries,
                List.copyOf(gaps), List.copyOf(unresolved));
    }

    /**
     * {@code localStatus} is reported as {@link PaymentCheckout#effectiveStatus} rather than the
     * stored value: an operator reading this needs to know the checkout is abandoned, not that a row
     * says {@code PENDING} because nothing has happened to that user since the window ran out.
     */
    private ActivationGap gap(GapKind kind, PaymentCheckout checkout,
                              PaymentProvider.RemoteCheckout remote, LocalDateTime now) {
        return new ActivationGap(
                kind,
                checkout.getSessionId(),
                userIdOf(checkout),
                checkout.getUser().getEmail(),
                checkout.getPlanKey(),
                checkout.getBillingPeriod(),
                checkout.getAmountCentavos(),
                checkout.effectiveStatus(now),
                checkout.getCreatedAt(),
                remote == null ? null : remote.paymentId(),
                remote == null ? null : remote.paidAt());
    }

    private static Long userIdOf(PaymentCheckout checkout) {
        return checkout.getUser() == null ? null : checkout.getUser().getId();
    }

    /**
     * Runs the reconciliation on demand from the command line, when — and only when — the
     * {@code --reconcile-payments} option is present: {@code scripts/reconcile-payments.sh} is the
     * intended caller. Every other boot, including every test context, returns here immediately.
     *
     * <p>It lives on the service because the check has no HTTP surface by design. Nothing about a
     * customer's payments should be reachable over the API by anyone but that customer, and an
     * operator-only endpoint is a new piece of authenticated attack surface for a report that a
     * shell command already delivers.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(RECONCILE_OPTION)) {
            return;
        }
        logReconciliationReport();

        // On this option the process exists only to print the report above, and it has to boot as
        // the full application to get there — `web-application-type=none` does not start, because
        // SecurityConfig needs the CORS bean the web context supplies. So the run ends itself
        // rather than leaving an operator's terminal holding a server. Spring's shutdown hook
        // closes the context on the way out, so this is a graceful stop, and an ordinary boot never
        // reaches it: it returns at the guard above.
        System.exit(0);
    }

    /**
     * Runs the check and writes the report to the log — the whole of what {@link #run} does before
     * it exits, kept separate so it is reachable from a test without taking the process down.
     *
     * <p>The transaction is opened here, explicitly, and that is not decoration: a call from
     * {@link #run} is a self-invocation, so it does not pass through the proxy and the
     * {@code @Transactional} on {@link #reconcileActivations()} never applies. The scan walks each
     * checkout's lazily-loaded user, and with {@code open-in-view=false} that read outside a
     * transaction is a {@code LazyInitializationException} — which is exactly how the first version
     * of this failed, on a green test suite. Same idiom as {@link #handleWebhook}.
     */
    public void logReconciliationReport() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        ReconciliationReport report = readOnly.execute(status -> reconcileActivations());

        log.info("payment_reconciliation checkouts_examined={} provider_queries={} discrepancies={} unresolved={}",
                report.checkoutsExamined(), report.providerQueries(),
                report.gaps().size(), report.unresolved().size());
        report.gaps().forEach(found -> log.warn("payment_reconciliation_gap {}", found.describe()));
        report.unresolved().forEach(entry -> log.warn("payment_reconciliation_unresolved {}", entry));
    }

    /**
     * What one run found. {@code checkoutsExamined} and {@code providerQueries} are reported so an
     * empty {@code gaps} list can be told apart from a run that examined nothing.
     */
    public record ReconciliationReport(
            int checkoutsExamined,
            int providerQueries,
            List<ActivationGap> gaps,
            List<String> unresolved
    ) {}

    public enum GapKind {
        /** The provider settled this session; nothing here was activated for it. */
        PROVIDER_PAID_LOCALLY_UNPAID,
        /** The checkout is marked paid here, and the user has no subscription row at all. */
        PAID_WITHOUT_SUBSCRIPTION
    }

    /**
     * One discrepancy, carrying every identifier needed to resolve it by hand: the session and
     * payment references to find it in the provider's dashboard, and the user, plan and amount to
     * know what to grant.
     */
    public record ActivationGap(
            GapKind kind,
            String sessionId,
            Long userId,
            String userEmail,
            PlanKey plan,
            BillingPeriod billingPeriod,
            int amountCentavos,
            CheckoutStatus localStatus,
            LocalDateTime checkoutCreatedAt,
            String providerPaymentId,
            Instant providerPaidAt
    ) {
        /** One line, in the log's {@code key=value} shape, complete enough to act on unaided. */
        public String describe() {
            return ("kind=%s session=%s user_id=%s email=%s plan=%s period=%s amount_centavos=%d "
                    + "local_status=%s checkout_created=%s provider_payment=%s provider_paid_at=%s")
                    .formatted(kind, sessionId, userId, userEmail, plan, billingPeriod, amountCentavos,
                            localStatus, manila(checkoutCreatedAt), providerPaymentId, manila(providerPaidAt));
        }

        /** Stored timestamps are UTC; an operator reads them in Manila time, offset included. */
        private static String manila(LocalDateTime stored) {
            return stored == null ? "-" : manila(stored.toInstant(ZoneOffset.UTC));
        }

        private static String manila(Instant instant) {
            return instant == null ? "-" : instant.atZone(MANILA).toOffsetDateTime().toString();
        }
    }
}
