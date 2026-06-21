package com.teng.app.gastosai;

import com.teng.app.gastosai.config.InMemoryRateLimiterStore;
import com.teng.app.gastosai.config.PublicRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRateLimitInterceptorTest {

    PublicRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PublicRateLimitInterceptor(3, new InMemoryRateLimiterStore());
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
    void xForwardedFor_usesFirstHop() throws Exception {
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
