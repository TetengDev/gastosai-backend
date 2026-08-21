package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.PricingProperties;
import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.payment.PayMongoProvider;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.PaymentService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TEN-150: a payment the provider accepted that never became a subscription must be findable.
 *
 * <p>Activation happens on one path only — a webhook delivery — so a delivery that is dropped, or
 * one this service answered 503 until the provider gave up retrying, leaves a customer who has paid
 * and has no access. Nothing in the database contradicts itself in that state: the checkout row
 * simply stays {@code PENDING}, exactly as an abandoned checkout does. The provider is the only
 * authority that can tell the two apart, so the check asks it.
 *
 * <p>The scenario below is the dropped webhook itself: a checkout is opened, the webhook never
 * arrives, and PayMongo says the session was paid.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gastos.paymongo.webhook-secret=integration-test-secret",
        "gastos.paymongo.secret-key=sk_test_placeholder"
})
class PaymentReconciliationTest extends PostgresBackedTest {

    private static final String DROPPED_SESSION = "cs_dropped_webhook_7hQ2mZr4tLbVx";
    private static final String PAYMENT_ID = "pay_dropped_2mZr4tLbVx";

    @Autowired PaymentService paymentService;
    @Autowired UserRepository userRepository;
    @Autowired PaymentCheckoutRepository checkoutRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionPlanRepository planRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean
    PaymentProvider paymentProvider;

    User payingUser;

    @BeforeEach
    void setUp() {
        payingUser = userRepository.save(User.builder()
                .name("Dropped Webhook User")
                .email("dropped-webhook@test.com")
                .password(passwordEncoder.encode("password"))
                .build());
        when(paymentProvider.key()).thenReturn("paymongo");
    }

    @Test
    void aPaymentTheProviderSettledButNothingActivatedIsReportedWithItsIdentifiers() {
        givenCheckout(DROPPED_SESSION, CheckoutStatus.PENDING);
        givenProviderSettled(DROPPED_SESSION);

        var report = paymentService.reconcileActivations();

        assertThat(report.checkoutsExamined()).isEqualTo(1);
        assertThat(report.providerQueries()).isEqualTo(1);
        assertThat(report.unresolved()).isEmpty();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.kind()).isEqualTo(PaymentService.GapKind.PROVIDER_PAID_LOCALLY_UNPAID);
            assertThat(gap.sessionId()).isEqualTo(DROPPED_SESSION);
            assertThat(gap.userId()).isEqualTo(payingUser.getId());
            assertThat(gap.userEmail()).isEqualTo("dropped-webhook@test.com");
            assertThat(gap.plan()).isEqualTo(PlanKey.PREMIUM);
            assertThat(gap.billingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
            assertThat(gap.amountCentavos()).isEqualTo(14900);
            assertThat(gap.localStatus()).isEqualTo(CheckoutStatus.PENDING);
            assertThat(gap.providerPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(gap.providerPaidAt()).isNotNull();
        });

        // Enough to resolve by hand without opening the database: who, what they bought, what it
        // cost, and the two references that find the money in PayMongo's dashboard.
        assertThat(report.gaps().getFirst().describe())
                .contains(DROPPED_SESSION)
                .contains("dropped-webhook@test.com")
                .contains("user_id=" + payingUser.getId())
                .contains("amount_centavos=14900")
                .contains("provider_payment=" + PAYMENT_ID)
                // Times are rendered in Manila, offset included, not as a naive timestamp.
                .contains("+08:00");
    }

    /**
     * Safe to run repeatedly: the check reports and repairs nothing, so a second run finds exactly
     * what the first did and neither leaves a trace. An operator investigating a payment must be
     * able to run this against production without weighing what it might change.
     */
    @Test
    void runningTheCheckTwiceReportsTheSameThingAndChangesNothing() {
        givenCheckout(DROPPED_SESSION, CheckoutStatus.PENDING);
        givenProviderSettled(DROPPED_SESSION);

        var first = paymentService.reconcileActivations();
        var second = paymentService.reconcileActivations();

        assertThat(second.gaps()).isEqualTo(first.gaps());
        assertThat(checkoutRepository.findBySessionId(DROPPED_SESSION).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.PENDING);
        assertThat(checkoutRepository.findBySessionId(DROPPED_SESSION).orElseThrow().getPaidAt()).isNull();
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(payingUser)).isEmpty();
    }

    /**
     * The other half: the check must be quiet when nothing is wrong, or nobody will read it. A
     * checkout that activated normally is not queried upstream at all — it already agrees with the
     * provider — and a checkout the customer simply abandoned is not a discrepancy either.
     */
    @Test
    void aCheckoutThatActivatedAndOneTheCustomerAbandonedAreBothQuiet() {
        givenCheckout("cs_activated_normally", CheckoutStatus.PAID);
        givenSubscription("cs_activated_normally");
        givenCheckout("cs_abandoned", CheckoutStatus.PENDING);
        when(paymentProvider.fetchCheckout("cs_abandoned")).thenReturn(Optional.of(
                new PaymentProvider.RemoteCheckout("cs_abandoned", false, 14900, null, null)));

        var report = paymentService.reconcileActivations();

        assertThat(report.gaps()).isEmpty();
        assertThat(report.checkoutsExamined()).isEqualTo(2);
        assertThat(report.providerQueries()).isEqualTo(1);
        verify(paymentProvider, never()).fetchCheckout("cs_activated_normally");
    }

    /**
     * A provider lookup that fails is the one answer that must not be read as "not paid": that
     * would report a clean reconciliation over the exact case the check exists for. It is recorded
     * as unresolved, and it does not abort the scan — one unreachable session must not hide the
     * discrepancy sitting behind it.
     */
    @Test
    void aFailedProviderLookupIsReportedUnresolvedAndDoesNotStopTheScan() {
        givenCheckout("cs_unreachable", CheckoutStatus.PENDING);
        givenCheckout(DROPPED_SESSION, CheckoutStatus.PENDING);
        when(paymentProvider.fetchCheckout("cs_unreachable"))
                .thenThrow(new RestClientException("502 Bad Gateway"));
        givenProviderSettled(DROPPED_SESSION);

        var report = paymentService.reconcileActivations();

        assertThat(report.unresolved()).singleElement().satisfies(entry -> assertThat(entry)
                .contains("cs_unreachable")
                .contains("502 Bad Gateway"));
        assertThat(report.gaps()).singleElement()
                .satisfies(gap -> assertThat(gap.sessionId()).isEqualTo(DROPPED_SESSION));
    }

    /**
     * The command-line path, which is the only way this is ever run for real, opens its own
     * transaction. Calling {@code reconcileActivations()} from a test reaches it through the proxy
     * and gets one for free, so the annotation alone made every assertion above pass while the
     * shell script died on a {@code LazyInitializationException}: the runner's call is a
     * self-invocation, the proxy is not involved, and the scan reads each checkout's lazy user.
     * This asserts the path the script actually takes, from outside any transaction.
     */
    @Test
    void theCommandLinePathRunsOutsideAnAmbientTransaction() {
        givenCheckout(DROPPED_SESSION, CheckoutStatus.PENDING);
        givenProviderSettled(DROPPED_SESSION);

        assertThatNoException().isThrownBy(() -> paymentService.logReconciliationReport());
    }

    private void givenCheckout(String sessionId, CheckoutStatus status) {
        checkoutRepository.save(PaymentCheckout.builder()
                .user(payingUser)
                .sessionId(sessionId)
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(status)
                .createdAt(LocalDateTime.now())
                .paidAt(status == CheckoutStatus.PAID ? LocalDateTime.now() : null)
                .build());
    }

    private void givenSubscription(String providerRef) {
        subscriptionRepository.save(UserSubscription.builder()
                .user(payingUser)
                .plan(planRepository.findByPlanKey(PlanKey.PREMIUM).orElseThrow())
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusMonths(1))
                .provider("paymongo")
                .providerRef(providerRef)
                .build());
    }

    private void givenProviderSettled(String sessionId) {
        when(paymentProvider.fetchCheckout(sessionId)).thenReturn(Optional.of(
                new PaymentProvider.RemoteCheckout(sessionId, true, 14900, PAYMENT_ID,
                        Instant.parse("2026-08-19T02:15:00Z"))));
    }

    /**
     * The provider half, against captured PayMongo response bodies rather than a mock of our own
     * record: the whole check rests on reading "was this settled?" out of a real retrieve response,
     * and that reading is where it can quietly be wrong.
     */
    @Nested
    class PayMongoRetrieve {

        private RestClient.Builder builder;
        private MockRestServiceServer server;
        private PayMongoProvider provider;

        @BeforeEach
        void bindMockServer() {
            builder = RestClient.builder().baseUrl("https://api.paymongo.test");
            server = MockRestServiceServer.bindTo(builder).build();
            provider = new PayMongoProvider(builder.build(), new PricingProperties(),
                    new ObjectMapper(), "http://localhost:5173");
        }

        @Test
        void aSettledSessionReadsAsPaidWithThePaymentIdentifiers() {
            expectRetrieve("cs_settled", """
                    {"data":{"id":"cs_settled","type":"checkout_session","attributes":{
                      "amount":14900,"currency":"PHP","payment_intent":{"id":"pi_1"},
                      "payments":[{"id":"pay_abc123","type":"payment","attributes":{
                        "amount":14900,"currency":"PHP","status":"paid","paid_at":1755500000}}]}}}
                    """);

            var remote = provider.fetchCheckout("cs_settled").orElseThrow();

            assertThat(remote.paid()).isTrue();
            assertThat(remote.paymentId()).isEqualTo("pay_abc123");
            assertThat(remote.amountCentavos()).isEqualTo(14900);
            assertThat(remote.paidAt()).isEqualTo(Instant.ofEpochSecond(1755500000L));
            server.verify();
        }

        @Test
        void aSessionWithNoSettledPaymentReadsAsUnpaid() {
            expectRetrieve("cs_open", """
                    {"data":{"id":"cs_open","type":"checkout_session","attributes":{
                      "amount":14900,"currency":"PHP","payments":[]}}}
                    """);

            var remote = provider.fetchCheckout("cs_open").orElseThrow();

            assertThat(remote.paid()).isFalse();
            assertThat(remote.paymentId()).isNull();
        }

        @Test
        void aSessionTheProviderDoesNotKnowIsEmptyRatherThanAnError() {
            server.expect(requestTo("https://api.paymongo.test/v1/checkout_sessions/cs_missing"))
                    .andRespond(withResourceNotFound().contentType(MediaType.APPLICATION_JSON)
                            .body("{\"errors\":[{\"code\":\"resource_not_found\"}]}"));

            assertThat(provider.fetchCheckout("cs_missing")).isEmpty();
        }

        /**
         * A rejected key must not read back as an empty, clean answer — that would report every
         * dropped payment as reconciled. Only 404 is an answer; everything else is a failure.
         */
        @Test
        void aRejectedKeyFailsLoudlyInsteadOfReadingAsUnpaid() {
            server.expect(requestTo("https://api.paymongo.test/v1/checkout_sessions/cs_any"))
                    .andRespond(withUnauthorizedRequest());

            assertThatThrownBy(() -> provider.fetchCheckout("cs_any"))
                    .isInstanceOf(HttpClientErrorException.class);
        }

        private void expectRetrieve(String sessionId, String body) {
            server.expect(requestTo("https://api.paymongo.test/v1/checkout_sessions/" + sessionId))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
    }
}
