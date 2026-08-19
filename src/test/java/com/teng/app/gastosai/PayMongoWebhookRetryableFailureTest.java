package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.exception.GlobalExceptionHandler;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.repository.WebhookEventRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-274: a webhook whose application fails on the database must be retryable, not a 400.
 *
 * <p>PayMongo retries an event until it sees a 2xx and does <em>not</em> retry a 4xx, so the status
 * is the whole of the contract: answer 400 to a delivery that failed halfway and the provider takes
 * it as handled, and a paid event is dropped in silence. {@code GlobalExceptionHandler} maps every
 * {@link DataAccessException} to 400, which is the right default for a user's own query and the
 * wrong one for a provider callback.
 *
 * <p>The live case is V20's {@code (user_id, provider_ref)} unique constraint on
 * {@code user_subscriptions}: activation writes the checkout's session id into the user's newest
 * subscription row, and an <em>older</em> row of the same user already carrying that reference makes
 * that write collide. The collision is real, not simulated — no mock stands in for the constraint.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gastos.paymongo.webhook-secret=integration-test-secret",
        "gastos.paymongo.secret-key=sk_test_placeholder"
})
class PayMongoWebhookRetryableFailureTest extends PostgresBackedTest {

    private static final String WEBHOOK_SECRET = "integration-test-secret";
    private static final String EVENT_ID = "evt_retryable_9WdKpQ2mZr4tLbVx";
    private static final String SESSION_ID = "cs_retry_conflict_001";

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PaymentCheckoutRepository checkoutRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionPlanRepository planRepository;
    @Autowired WebhookEventRepository webhookEventRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean
    PaymentProvider paymentProvider;

    MockMvc mockMvc;
    User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        testUser = userRepository.save(User.builder()
                .name("Retryable Failure User")
                .email("webhook-retryable@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        when(paymentProvider.key()).thenReturn("paymongo");
    }

    @Test
    void deliveryThatCollidesOnTheProviderRefConstraintIsAnsweredRetryably() throws Exception {
        givenSubscriptionWithProviderRef(SESSION_ID, LocalDateTime.now().minusDays(2));
        givenSubscriptionWithProviderRef("cs_some_earlier_session", LocalDateTime.now().minusDays(1));
        givenPendingCheckout(SESSION_ID);

        deliver(capturedPaidEvent(EVENT_ID, SESSION_ID))
                .andExpect(status().is5xxServerError())
                .andExpect(status().isServiceUnavailable());

        // Nothing was half-applied, and — the point of the status — nothing was recorded that would
        // make the retry a no-op: the event id is unclaimed, so the redelivery is applied for real.
        assertThat(webhookEventRepository.existsByProviderAndEventId("paymongo", EVENT_ID)).isFalse();
        assertThat(checkoutRepository.findBySessionId(SESSION_ID).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.PENDING);
        assertThat(subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(testUser).orElseThrow()
                .getProviderRef()).isEqualTo("cs_some_earlier_session");
    }

    /**
     * The other half of the fix: this is not a blanket remap. An integrity failure reached through
     * an ordinary authenticated request keeps the 400 it has always answered — only a request on a
     * provider callback path is turned into a retryable status.
     */
    @Test
    void anOrdinaryRequestKeepsItsBadRequestOnTheSameException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(null);
        DataAccessException failure = new DataIntegrityViolationException("duplicate key value");

        assertThat(handler.dataAccess(failure, requestTo("/api/v1/expenses")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.dataAccess(failure, requestTo("/webhooks/paymongo")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    /**
     * A subscription row for the test user carrying {@code providerRef}. {@code created_at} is set
     * afterwards because {@code @PrePersist} overwrites it: activation picks the user's
     * <em>newest</em> row to update, so which row is newest decides which one the write collides
     * with, and leaving that to two timestamps taken microseconds apart would be a coin flip.
     */
    private void givenSubscriptionWithProviderRef(String providerRef, LocalDateTime createdAt) {
        var plan = planRepository.findByPlanKey(PlanKey.FREE).orElseThrow();
        var saved = subscriptionRepository.save(UserSubscription.builder()
                .user(testUser)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .provider("paymongo")
                .providerRef(providerRef)
                .build());
        jdbcTemplate.update("UPDATE user_subscriptions SET created_at = ? WHERE id = ?",
                createdAt, saved.getId());
    }

    private void givenPendingCheckout(String sessionId) {
        checkoutRepository.save(PaymentCheckout.builder()
                .user(testUser)
                .sessionId(sessionId)
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
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
