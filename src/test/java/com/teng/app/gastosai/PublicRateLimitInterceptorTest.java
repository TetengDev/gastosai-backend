package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AiKeyContextInterceptor;
import com.teng.app.gastosai.config.AiRateLimitInterceptor;
import com.teng.app.gastosai.config.AuthenticatedWriteRateLimitInterceptor;
import com.teng.app.gastosai.config.FeatureAccessInterceptor;
import com.teng.app.gastosai.config.InMemoryRateLimiterStore;
import com.teng.app.gastosai.config.PublicRateLimitInterceptor;
import com.teng.app.gastosai.config.ViewAsInterceptor;
import com.teng.app.gastosai.config.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PublicRateLimitInterceptorTest {

    PublicRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PublicRateLimitInterceptor(3, WEBHOOK_LIMIT, new InMemoryRateLimiterStore());
    }

    /** Deliberately larger than the interactive limit of 3, as it is in production. */
    private static final int WEBHOOK_LIMIT = 8;

    private MockHttpServletRequest webhookRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/paymongo");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private boolean handle(MockHttpServletRequest request) throws Exception {
        return interceptor.preHandle(request, response(), null);
    }

    @Test
    void allowsUpToLimit_thenBlocks() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(handle(request)).isTrue();
        assertThat(handle(request)).isTrue();
        assertThat(handle(request)).isTrue();
        assertThat(handle(request)).isFalse();
    }

    @Test
    void differentIps_areIndependent() throws Exception {
        MockHttpServletRequest r1 = new MockHttpServletRequest();
        r1.setRemoteAddr("10.0.0.1");

        MockHttpServletRequest r2 = new MockHttpServletRequest();
        r2.setRemoteAddr("10.0.0.2");

        for (int i = 0; i < 3; i++) {
            handle(r1);
        }
        assertThat(handle(r1)).isFalse();
        assertThat(handle(r2)).isTrue();
    }

    @Test
    void xForwardedFor_usesRightmostEntry() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.1.1.1");
        request.setRemoteAddr("127.0.0.1");

        for (int i = 0; i < 3; i++) {
            handle(request);
        }
        MockHttpServletResponse resp = response();
        boolean allowed = interceptor.preHandle(request, resp, null);
        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(429);
    }

    @Test
    void xForwardedFor_spoofedLeftmost_doesNotShareBucketWithRightmost() throws Exception {
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.addHeader("X-Forwarded-For", "1.2.3.4, 10.1.1.99");
        spoofed.setRemoteAddr("127.0.0.1");

        MockHttpServletRequest real = new MockHttpServletRequest();
        real.addHeader("X-Forwarded-For", "99.99.99.99, 10.1.1.99");
        real.setRemoteAddr("127.0.0.1");

        for (int i = 0; i < 3; i++) {
            handle(spoofed);
        }
        assertThat(handle(spoofed)).isFalse();
        assertThat(handle(real)).isFalse();
    }

    @Test
    void xForwardedFor_spoofedLeftmost_independentFromDifferentRightmost() throws Exception {
        MockHttpServletRequest attackerSpoofing = new MockHttpServletRequest();
        attackerSpoofing.addHeader("X-Forwarded-For", "victim.ip, 10.0.0.5");

        MockHttpServletRequest legitRequest = new MockHttpServletRequest();
        legitRequest.setRemoteAddr("10.0.0.6");

        for (int i = 0; i < 3; i++) {
            handle(attackerSpoofing);
        }
        assertThat(handle(attackerSpoofing)).isFalse();
        assertThat(handle(legitRequest)).isTrue();
    }

    @Test
    void webhook_isRateLimited_onItsOwnLargerBudget() throws Exception {
        MockHttpServletRequest webhook = webhookRequest("10.0.0.20");

        // A burst the size of the interactive limit — what a run of genuine PayMongo events, or a
        // backlog being redelivered, looks like — still reaches the handler.
        for (int i = 0; i < WEBHOOK_LIMIT; i++) {
            assertThat(handle(webhook)).as("webhook %d of %d", i + 1, WEBHOOK_LIMIT).isTrue();
        }

        MockHttpServletResponse resp = response();
        assertThat(interceptor.preHandle(webhook, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(429);
    }

    @Test
    void webhook_andInteractiveTraffic_doNotShareABucket() throws Exception {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/auth/login");
        login.setRemoteAddr("10.0.0.21");

        // Same IP exhausts the interactive window; the webhook budget is untouched by it.
        for (int i = 0; i < 3; i++) {
            handle(login);
        }
        assertThat(handle(login)).isFalse();
        assertThat(handle(webhookRequest("10.0.0.21"))).isTrue();
    }

    @Test
    void webhookRegistration_coversThePayMongoPath() {
        PublicRateLimitInterceptor publicLimiter =
                new PublicRateLimitInterceptor(3, WEBHOOK_LIMIT, new InMemoryRateLimiterStore());
        WebConfig config = new WebConfig(
                mock(FeatureAccessInterceptor.class),
                mock(AiRateLimitInterceptor.class),
                mock(AiKeyContextInterceptor.class),
                mock(ViewAsInterceptor.class),
                publicLimiter,
                mock(AuthenticatedWriteRateLimitInterceptor.class));

        ExposedRegistry registry = new ExposedRegistry();
        config.addInterceptors(registry);

        List<String> patterns = registry.interceptors().stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() == publicLimiter)
                .map(MappedInterceptor::getIncludePathPatterns)
                .filter(java.util.Objects::nonNull)
                .flatMap(java.util.Arrays::stream)
                .toList();

        assertThat(patterns).contains("/webhooks/paymongo");
    }

    /** {@code InterceptorRegistry#getInterceptors} is protected; a subclass is the way to read it. */
    private static class ExposedRegistry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }

    @Test
    void blocked_response_is429WithJsonBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        for (int i = 0; i < 3; i++) {
            handle(request);
        }
        MockHttpServletResponse resp = response();
        interceptor.preHandle(request, resp, null);

        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getContentAsString()).contains("Too Many Requests");
    }
}
