package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.SubscriptionService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-147: a webhook PayMongo delivers more than once must have the effect of one delivery.
 *
 * <p>PayMongo retries an event until it sees a 2xx, and a retry carries the <em>same</em>
 * {@code data.id} — the provider's event id. That id, not the shape of the payload, is the
 * idempotency key: two deliveries of one event are the same event however their bytes compare,
 * and two distinct events are distinct however alike they look.
 *
 * <p>{@link PaymentApiIntegrationTest#webhookIsIdempotentOnReplay} already covers the easy half,
 * where the first delivery has fully committed before the second arrives and the checkout's own
 * {@code PAID} status absorbs it. What that guard cannot absorb is a retry that overlaps the
 * delivery it is retrying — both read a {@code PENDING} checkout, both activate — and it is not
 * an idempotency key at all: it keys on the session the payload names, so it lets a second
 * delivery of one event act on whatever else that payload points at.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gastos.paymongo.webhook-secret=integration-test-secret",
        "gastos.paymongo.secret-key=sk_test_placeholder"
})
class PayMongoWebhookReplayTest extends PostgresBackedTest {

    private static final String WEBHOOK_SECRET = "integration-test-secret";

    /** The event id carried by the captured payload below; a retry repeats it verbatim. */
    private static final String EVENT_ID = "evt_yXqBmYbGrPScv7fJ9wLdT2Ck";

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PaymentCheckoutRepository checkoutRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean
    PaymentProvider paymentProvider;

    /**
     * Spied, not mocked: activation must really happen, and the count of calls is what "one
     * activation" means when both deliveries end in the same 2xx and the same final state.
     */
    @MockitoSpyBean
    SubscriptionService subscriptionService;

    MockMvc mockMvc;
    User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        testUser = userRepository.save(User.builder()
                .name("Replay Test User")
                .email("webhook-replay@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        when(paymentProvider.key()).thenReturn("paymongo");
    }

    @Test
    void replayedDeliveryOfACapturedEventActivatesExactlyOnce() throws Exception {
        givenPendingCheckout("cs_replay_captured_001", BillingPeriod.MONTHLY, 14900);
        String body = capturedPaidEvent(EVENT_ID, "cs_replay_captured_001");

        deliver(body).andExpect(status().isOk());

        var afterFirst = subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(testUser).orElseThrow();
        LocalDateTime periodEndAfterFirst = afterFirst.getCurrentPeriodEnd();
        LocalDateTime paidAtAfterFirst = checkoutRepository.findBySessionId("cs_replay_captured_001")
                .orElseThrow().getPaidAt();

        deliver(body).andExpect(status().isOk());

        verify(subscriptionService, times(1))
                .activate(any(), any(), any(), any(), any());

        assertThat(subscriptionRepository.findAll()).hasSize(1);
        var afterSecond = subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(testUser).orElseThrow();
        assertThat(afterSecond.getId()).isEqualTo(afterFirst.getId());
        assertThat(afterSecond.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(afterSecond.getPlan().getPlanKey()).isEqualTo(PlanKey.PREMIUM);
        // The paid period must not have been extended by the retry — that is the double charge.
        assertThat(afterSecond.getCurrentPeriodEnd()).isEqualTo(periodEndAfterFirst);

        var checkout = checkoutRepository.findBySessionId("cs_replay_captured_001").orElseThrow();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkout.getPaidAt()).isEqualTo(paidAtAfterFirst);
    }

    /**
     * The idempotency key is the provider's event id, so a second delivery of an event already
     * seen is discarded before anything in its payload is read. Pinned with a payload that is
     * <em>not</em> byte-equal to the first — same event id, a different session — because a
     * dedupe keyed on payload equality, or on the named checkout's status, would let this one
     * through and activate a checkout that nobody paid for.
     */
    @Test
    void secondDeliveryOfTheSameEventIdIsDiscardedWhateverItsPayloadSays() throws Exception {
        givenPendingCheckout("cs_replay_paid_002", BillingPeriod.MONTHLY, 14900);
        givenPendingCheckout("cs_replay_unpaid_003", BillingPeriod.ANNUAL, 129000);

        deliver(capturedPaidEvent(EVENT_ID, "cs_replay_paid_002")).andExpect(status().isOk());
        deliver(capturedPaidEvent(EVENT_ID, "cs_replay_unpaid_003")).andExpect(status().isOk());

        verify(subscriptionService, times(1))
                .activate(any(), any(), any(), any(), any());

        assertThat(checkoutRepository.findBySessionId("cs_replay_paid_002").orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.PAID);
        var untouched = checkoutRepository.findBySessionId("cs_replay_unpaid_003").orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(untouched.getPaidAt()).isNull();
        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }

    /**
     * The retry that overlaps the delivery it is retrying. Both requests are in flight at once and
     * both see a {@code PENDING} checkout, so the status guard cannot separate them; only a key
     * the database itself enforces can.
     *
     * <p>The first delivery is held inside {@code activate} until the second has reached the
     * database, which is the interleaving that matters. Holding it longer than the second needs
     * only makes the second wait; the assertions do not depend on which order they finish in, so
     * a slow machine cannot flip the result.
     */
    @Test
    void concurrentRedeliveryOfTheSameEventActivatesOnceAndStillAnswers2xx() throws Exception {
        givenPendingCheckout("cs_replay_concurrent_004", BillingPeriod.MONTHLY, 14900);
        String body = capturedPaidEvent(EVENT_ID, "cs_replay_concurrent_004");

        CountDownLatch insideActivate = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            insideActivate.countDown();
            release.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(subscriptionService).activate(any(), any(), any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> deliver(body).andReturn().getResponse().getStatus());
            assertThat(insideActivate.await(10, TimeUnit.SECONDS))
                    .as("the first delivery reached activation").isTrue();

            Future<Integer> second = pool.submit(() -> deliver(body).andReturn().getResponse().getStatus());
            // Long enough for the second delivery to reach its own claim on the event id, where it
            // either waits for the first transaction or is turned away by it.
            Thread.sleep(500);
            release.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).as("first delivery status").isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS)).as("redelivery status").isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        verify(subscriptionService, times(1))
                .activate(any(), any(), any(), any(), any());
        assertThat(subscriptionRepository.findAll()).hasSize(1);
        assertThat(subscriptionRepository.findAll().getFirst().getStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);

        List<PaymentCheckout> paid = checkoutRepository.findAll().stream()
                .filter(c -> c.getStatus() == CheckoutStatus.PAID)
                .toList();
        assertThat(paid).hasSize(1);
    }

    private void givenPendingCheckout(String sessionId, BillingPeriod period, int centavos) {
        checkoutRepository.save(PaymentCheckout.builder()
                .user(testUser)
                .sessionId(sessionId)
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(period)
                .amountCentavos(centavos)
                .status(CheckoutStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private org.springframework.test.web.servlet.ResultActions deliver(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String header = "t=" + timestamp + ",te=" + computeHmac(timestamp + "." + body, WEBHOOK_SECRET);
        return mockMvc.perform(post("/webhooks/paymongo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Paymongo-Signature", header));
    }

    /**
     * A {@code checkout_session.payment.paid} event as PayMongo sends it — the envelope's
     * {@code data.id} is the event id, and the checkout session is nested under
     * {@code data.attributes.data}. Trimmed of the line items and billing block, which nothing
     * here reads, but the shape of the two ids and the type is verbatim.
     */
    private static String capturedPaidEvent(String eventId, String sessionId) {
        return """
                {
                  "data": {
                    "id": "%s",
                    "type": "event",
                    "attributes": {
                      "type": "checkout_session.payment.paid",
                      "livemode": false,
                      "created_at": 1755500000,
                      "updated_at": 1755500000,
                      "previous_data": {},
                      "data": {
                        "id": "%s",
                        "type": "checkout_session",
                        "attributes": {
                          "currency": "PHP",
                          "description": "Gastosai Premium",
                          "payment_method_used": "gcash",
                          "reference_number": "gastosai-premium",
                          "status": "active",
                          "paid_at": 1755500000
                        }
                      }
                    }
                  }
                }""".formatted(eventId, sessionId);
    }

    private static String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
