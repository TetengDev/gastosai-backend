package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.payment.PaymentProvider;
import com.teng.app.gastosai.repository.PaymentCheckoutRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.paymongo.webhook-secret=integration-test-secret",
        "gastos.paymongo.secret-key=sk_test_placeholder"
})
class PaymentApiIntegrationTest {

    private static final String WEBHOOK_SECRET = "integration-test-secret";

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PaymentCheckoutRepository checkoutRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionPlanRepository planRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean
    PaymentProvider paymentProvider;

    MockMvc mockMvc;
    String authHeader;
    User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        subscriptionRepository.deleteAll();
        checkoutRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .name("Test User")
                .email("payment-test@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        authHeader = "Bearer " + jwtUtil.generate(testUser.getEmail());

        when(paymentProvider.key()).thenReturn("paymongo");
        when(paymentProvider.createCheckout(any(), any(), any()))
                .thenReturn(new PaymentProvider.PaymentCheckoutSession(
                        "cs_test_session_123",
                        "https://checkout.paymongo.com/cs_test_session_123"
                ));
    }

    @Test
    void pricingIsPublicAndReturnsBothItems() throws Exception {
        mockMvc.perform(get("/subscription/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].planKey").value("PREMIUM"))
                .andExpect(jsonPath("$[0].period").value("MONTHLY"))
                .andExpect(jsonPath("$[0].amountCentavos").value(14900))
                .andExpect(jsonPath("$[0].currency").value("PHP"))
                .andExpect(jsonPath("$[1].period").value("ANNUAL"))
                .andExpect(jsonPath("$[1].amountCentavos").value(129000));
    }

    @Test
    void checkoutRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/subscription/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"MONTHLY\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutReturnsUrlAndCreatesPendingRow() throws Exception {
        mockMvc.perform(post("/subscription/checkout")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"MONTHLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.paymongo.com/cs_test_session_123"));

        var checkout = checkoutRepository.findBySessionId("cs_test_session_123");
        assertThat(checkout).isPresent();
        assertThat(checkout.get().getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(checkout.get().getBillingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(checkout.get().getPlanKey()).isEqualTo(PlanKey.PREMIUM);
    }

    @Test
    void webhookWithValidSignatureActivatesSubscription() throws Exception {
        PaymentCheckout checkout = checkoutRepository.save(PaymentCheckout.builder()
                .user(testUser)
                .sessionId("cs_test_webhook_456")
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(CheckoutStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build());

        String body = buildWebhookBody("cs_test_webhook_456");
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String sig = computeHmac(timestamp + "." + body, WEBHOOK_SECRET);
        String header = "t=" + timestamp + ",te=" + sig;

        mockMvc.perform(post("/webhooks/paymongo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Paymongo-Signature", header))
                .andExpect(status().isOk());

        var updatedCheckout = checkoutRepository.findBySessionId("cs_test_webhook_456");
        assertThat(updatedCheckout).isPresent();
        assertThat(updatedCheckout.get().getStatus()).isEqualTo(CheckoutStatus.PAID);
        assertThat(updatedCheckout.get().getPaidAt()).isNotNull();

        var subscription = subscriptionRepository.findFirstByUserOrderByCreatedAtDesc(testUser);
        assertThat(subscription).isPresent();
        assertThat(subscription.get().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.get().getPlan().getPlanKey()).isEqualTo(PlanKey.PREMIUM);
        assertThat(subscription.get().getCurrentPeriodEnd()).isAfter(LocalDateTime.now());
    }

    @Test
    void webhookIsIdempotentOnReplay() throws Exception {
        PaymentCheckout checkout = checkoutRepository.save(PaymentCheckout.builder()
                .user(testUser)
                .sessionId("cs_test_idempotent_789")
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.ANNUAL)
                .amountCentavos(129000)
                .status(CheckoutStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build());

        String body = buildWebhookBody("cs_test_idempotent_789");
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String sig = computeHmac(timestamp + "." + body, WEBHOOK_SECRET);
        String header = "t=" + timestamp + ",te=" + sig;

        mockMvc.perform(post("/webhooks/paymongo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Paymongo-Signature", header))
                .andExpect(status().isOk());

        mockMvc.perform(post("/webhooks/paymongo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Paymongo-Signature", header))
                .andExpect(status().isOk());

        long paidCheckouts = checkoutRepository.findAll().stream()
                .filter(c -> "cs_test_idempotent_789".equals(c.getSessionId()))
                .filter(c -> c.getStatus() == CheckoutStatus.PAID)
                .count();
        assertThat(paidCheckouts).isEqualTo(1);
    }

    @Test
    void webhookWithWrongSignatureReturns401() throws Exception {
        String body = buildWebhookBody("cs_bad_sig_000");
        String header = "t=1700000003,te=invalidsig";

        mockMvc.perform(post("/webhooks/paymongo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Paymongo-Signature", header))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSubscriptionReturnsFreePlanForNewUser() throws Exception {
        mockMvc.perform(get("/subscription")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        // currentPeriodEnd and billingPeriod are null for a free user — not asserting the null literal
    }

    @Test
    void duplicateProviderRefForSameUserIsRejected() {
        var plan = planRepository.findByPlanKey(PlanKey.PREMIUM).orElseThrow();

        subscriptionRepository.saveAndFlush(UserSubscription.builder()
                .user(testUser)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusMonths(1))
                .provider("paymongo")
                .providerRef("cs_duplicate_delivery")
                .build());

        assertThatThrownBy(() -> subscriptionRepository.saveAndFlush(UserSubscription.builder()
                .user(testUser)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusMonths(1))
                .provider("paymongo")
                .providerRef("cs_duplicate_delivery")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String buildWebhookBody(String sessionId) {
        return "{\"data\":{\"attributes\":{\"type\":\"checkout_session.payment.paid\",\"data\":{\"id\":\"" + sessionId + "\"}}}}";
    }

    private static String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
