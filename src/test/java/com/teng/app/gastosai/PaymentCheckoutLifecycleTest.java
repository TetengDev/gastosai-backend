package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.PaymentService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The checkout lifecycle (TEN-149) as it is actually reached: through {@code startCheckout} and
 * through a signed webhook, against the real database.
 *
 * <p>{@code PaymentCheckoutTest} pins the transition rules on the entity. What can only be shown
 * here is that they are wired up — that an abandoned checkout really is written down as expired when
 * the user comes back, that a refused payment really does land as {@code FAILED} and not as the
 * abandonment it is not, and that neither of them can strand a user behind their own old row.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gastos.paymongo.webhook-secret=lifecycle-test-secret",
        "gastos.paymongo.secret-key=sk_test_placeholder"
})
class PaymentCheckoutLifecycleTest extends PostgresBackedTest {

    private static final String WEBHOOK_SECRET = "lifecycle-test-secret";

    @Autowired PaymentService paymentService;
    @Autowired PaymentCheckoutRepository checkoutRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean
    PaymentProvider paymentProvider;

    User user;
    final AtomicInteger sessionCounter = new AtomicInteger();
    final AtomicInteger eventCounter = new AtomicInteger();

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .name("Lifecycle User")
                .email("checkout-lifecycle@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        when(paymentProvider.key()).thenReturn("paymongo");
        // A distinct session per call, the way the provider issues them — two checkouts sharing an
        // id would collide on the unique constraint and hide whatever the test meant to show.
        when(paymentProvider.createCheckout(any(), any(), any())).thenAnswer(invocation -> {
            String id = "cs_live_" + sessionCounter.incrementAndGet();
            return new PaymentProvider.PaymentCheckoutSession(id, "https://checkout.paymongo.com/" + id);
        });
    }

    // -------------------------------------------------------------------------------------------
    // Abandonment: the window elapses, the status says so, a new checkout starts
    // -------------------------------------------------------------------------------------------

    @Test
    void startingAgainExpiresAnAbandonedCheckoutAndCreatesALiveOne() {
        PaymentCheckout abandoned = givenCheckout("cs_abandoned", CheckoutStatus.PENDING, hoursAgo(30));

        String url = paymentService.startCheckout(user, BillingPeriod.MONTHLY);

        assertThat(url).isEqualTo("https://checkout.paymongo.com/cs_live_1");
        assertThat(statusOf(abandoned.getSessionId())).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(statusOf("cs_live_1")).isEqualTo(CheckoutStatus.PENDING);
        assertThat(checkoutRepository.findBySessionId(abandoned.getSessionId()).orElseThrow().getPaidAt())
                .isNull();
    }

    @Test
    void aCheckoutStillInsideItsWindowIsLeftPending() {
        givenCheckout("cs_still_open", CheckoutStatus.PENDING, hoursAgo(2));

        paymentService.startCheckout(user, BillingPeriod.MONTHLY);

        assertThat(statusOf("cs_still_open")).isEqualTo(CheckoutStatus.PENDING);
        assertThat(statusOf("cs_live_1")).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test
    void everyAbandonedCheckoutOfTheUserIsResolvedInOneSweep() {
        givenCheckout("cs_old_1", CheckoutStatus.PENDING, hoursAgo(72));
        givenCheckout("cs_old_2", CheckoutStatus.PENDING, hoursAgo(48));
        givenCheckout("cs_old_3", CheckoutStatus.PENDING, hoursAgo(25));

        paymentService.startCheckout(user, BillingPeriod.ANNUAL);

        assertThat(statusOf("cs_old_1")).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(statusOf("cs_old_2")).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(statusOf("cs_old_3")).isEqualTo(CheckoutStatus.EXPIRED);
    }

    @Test
    void theSweepNeverTouchesAnotherUsersCheckouts() {
        User other = userRepository.save(User.builder()
                .name("Other User")
                .email("checkout-other@test.com")
                .password(passwordEncoder.encode("password"))
                .build());
        checkoutRepository.save(PaymentCheckout.builder()
                .user(other)
                .sessionId("cs_other_abandoned")
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(CheckoutStatus.PENDING)
                .createdAt(hoursAgo(30))
                .build());

        paymentService.startCheckout(user, BillingPeriod.MONTHLY);

        assertThat(statusOf("cs_other_abandoned")).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test
    void anAlreadyPaidCheckoutIsNotExpiredBySweeping() {
        PaymentCheckout paid = givenCheckout("cs_paid_long_ago", CheckoutStatus.PAID, hoursAgo(200));
        paid.setPaidAt(hoursAgo(199));
        checkoutRepository.save(paid);

        paymentService.startCheckout(user, BillingPeriod.MONTHLY);

        assertThat(statusOf("cs_paid_long_ago")).isEqualTo(CheckoutStatus.PAID);
    }

    // -------------------------------------------------------------------------------------------
    // Failure: distinguishable from abandonment
    // -------------------------------------------------------------------------------------------

    @Test
    void aRefusedPaymentLandsAsFailedNotExpired() {
        givenCheckout("cs_declined", CheckoutStatus.PENDING, hoursAgo(1));

        deliver("checkout_session.payment.failed", "cs_declined");

        assertThat(statusOf("cs_declined")).isEqualTo(CheckoutStatus.FAILED);
        assertThat(statusOf("cs_declined")).isNotEqualTo(CheckoutStatus.EXPIRED);
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).isEmpty();
    }

    @Test
    void aFailedCheckoutDoesNotBlockANewOne() {
        givenCheckout("cs_declined", CheckoutStatus.PENDING, hoursAgo(1));
        deliver("checkout_session.payment.failed", "cs_declined");

        String url = paymentService.startCheckout(user, BillingPeriod.MONTHLY);

        assertThat(url).isEqualTo("https://checkout.paymongo.com/cs_live_1");
        assertThat(statusOf("cs_declined")).isEqualTo(CheckoutStatus.FAILED);
        assertThat(statusOf("cs_live_1")).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test
    void aFailureEventNeverOverridesAPaidCheckout() {
        givenCheckout("cs_paid_then_failed", CheckoutStatus.PENDING, hoursAgo(1));
        deliver("checkout_session.payment.paid", "cs_paid_then_failed");

        deliver("checkout_session.payment.failed", "cs_paid_then_failed");

        assertThat(statusOf("cs_paid_then_failed")).isEqualTo(CheckoutStatus.PAID);
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).isPresent();
    }

    /**
     * A failure event naming something that is not a checkout session here — the payment-scoped
     * shape PayMongo also emits — is acknowledged and changes nothing, rather than being retried
     * forever or, worse, matching the wrong row.
     */
    @Test
    void aFailureEventForAnUnknownSessionChangesNothing() {
        givenCheckout("cs_untouched", CheckoutStatus.PENDING, hoursAgo(1));

        deliver("payment.failed", "pay_something_else");

        assertThat(statusOf("cs_untouched")).isEqualTo(CheckoutStatus.PENDING);
    }

    // -------------------------------------------------------------------------------------------
    // The two resolutions still yield to the provider
    // -------------------------------------------------------------------------------------------

    @Test
    void aPaymentThatSettlesAfterExpiryStillActivatesTheSubscription() {
        givenCheckout("cs_late_payment", CheckoutStatus.EXPIRED, hoursAgo(30));

        deliver("checkout_session.payment.paid", "cs_late_payment");

        assertThat(statusOf("cs_late_payment")).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkoutRepository.findBySessionId("cs_late_payment").orElseThrow().getPaidAt())
                .isNotNull();
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).isPresent();
    }

    @Test
    void aPaymentThatSettlesAfterAFailureStillActivatesTheSubscription() {
        givenCheckout("cs_retry_settled", CheckoutStatus.FAILED, hoursAgo(2));

        deliver("checkout_session.payment.paid", "cs_retry_settled");

        assertThat(statusOf("cs_retry_settled")).isEqualTo(CheckoutStatus.PAID);
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user)).isPresent();
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static LocalDateTime hoursAgo(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    private PaymentCheckout givenCheckout(String sessionId, CheckoutStatus status, LocalDateTime createdAt) {
        return checkoutRepository.save(PaymentCheckout.builder()
                .user(user)
                .sessionId(sessionId)
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(status)
                .createdAt(createdAt)
                .build());
    }

    private CheckoutStatus statusOf(String sessionId) {
        return checkoutRepository.findBySessionId(sessionId).orElseThrow().getStatus();
    }

    /** One signed delivery, with a fresh event id so it is never mistaken for a replay. */
    private void deliver(String eventType, String sessionId) {
        String body = ("{\"data\":{\"id\":\"evt_%s_%d\",\"attributes\":{\"type\":\"%s\","
                + "\"data\":{\"id\":\"%s\"}}}}")
                .formatted(eventType.replace('.', '_'), eventCounter.incrementAndGet(),
                        eventType, sessionId);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        paymentService.handleWebhook(body, "t=" + timestamp + ",te=" + hmac(timestamp + "." + body));
    }

    private static String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the test webhook body", e);
        }
    }
}
