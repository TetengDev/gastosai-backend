package com.teng.app.gastosai;

import com.teng.app.gastosai.config.ClientIps;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpsTest {

    @Test
    void noHeader_returnsRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("1.2.3.4");
        assertThat(ClientIps.extract(req)).isEqualTo("1.2.3.4");
    }

    @Test
    void singleEntry_returnsIt() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5");
        req.setRemoteAddr("127.0.0.1");
        assertThat(ClientIps.extract(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void multipleEntries_returnsRightmost() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "spoofed.client, 10.10.10.1, 172.16.0.1");
        req.setRemoteAddr("127.0.0.1");
        assertThat(ClientIps.extract(req)).isEqualTo("172.16.0.1");
    }

    @Test
    void spoofedLeftmost_ignoredInFavourOfRightmost() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        req.setRemoteAddr("127.0.0.1");
        assertThat(ClientIps.extract(req)).isEqualTo("2.2.2.2");
    }

    @Test
    void blankHeader_fallsBackToRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "   ");
        req.setRemoteAddr("5.5.5.5");
        assertThat(ClientIps.extract(req)).isEqualTo("5.5.5.5");
    }

    @Test
    void rightmostEntryTrimmed() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1,  9.9.9.9 ");
        assertThat(ClientIps.extract(req)).isEqualTo("9.9.9.9");
    }
}
