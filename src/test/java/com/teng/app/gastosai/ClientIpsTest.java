package com.teng.app.gastosai;

import com.teng.app.gastosai.config.ClientIps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address every IP-keyed abuse control is keyed on (TEN-425, pentest finding H1).
 *
 * <p>The behaviour under test is which of two inputs wins: the transport peer, which a client
 * cannot choose, and {@code X-Forwarded-For}, which it can set to anything. Before TEN-425 the
 * header always won, so a rotating value bought a fresh rate-limit and registration bucket per
 * request.
 *
 * <p>{@code ClientIps} holds its trusted set statically, so every test restores the empty default.
 */
class ClientIpsTest {

    @BeforeEach
    @AfterEach
    void resetTrustedProxies() {
        ClientIps.configureTrustedProxies("");
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(peer);
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        return req;
    }

    // ---- no trusted proxy configured: the header is not identity ---------------------------------

    @Test
    void noHeader_returnsRemoteAddr() {
        assertThat(ClientIps.extract(request("1.2.3.4", null))).isEqualTo("1.2.3.4");
    }

    @Test
    void noTrustedProxy_headerIsIgnored() {
        assertThat(ClientIps.extract(request("1.2.3.4", "203.0.113.5"))).isEqualTo("1.2.3.4");
    }

    @Test
    void noTrustedProxy_everyHopIsIgnored() {
        assertThat(ClientIps.extract(request("1.2.3.4", "9.9.9.9, 10.10.10.1, 172.16.0.1")))
                .isEqualTo("1.2.3.4");
    }

    /**
     * The bypass itself: fourteen requests with fourteen header values are fourteen requests from
     * one address, so they share one bucket instead of each getting a fresh one.
     */
    @Test
    void rotatingSpoofedHeader_yieldsOneKeyNotMany() {
        for (int i = 1; i <= 14; i++) {
            assertThat(ClientIps.extract(request("198.51.100.1", "203.0.113." + i)))
                    .as("attempt %d must key on the peer, not the header", i)
                    .isEqualTo("198.51.100.1");
        }
    }

    @Test
    void blankHeader_fallsBackToRemoteAddr() {
        assertThat(ClientIps.extract(request("5.5.5.5", "   "))).isEqualTo("5.5.5.5");
    }

    // ---- a trusted proxy in front: its rightmost hop is believed ---------------------------------

    @Test
    void trustedProxy_returnsRightmostUntrustedHop() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", "1.1.1.1, 203.0.113.5")))
                .isEqualTo("203.0.113.5");
    }

    @Test
    void trustedProxy_rightmostEntryTrimmed() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", "1.1.1.1,  9.9.9.9 ")))
                .isEqualTo("9.9.9.9");
    }

    @Test
    void trustedProxy_skipsFurtherTrustedHopsInTheChain() {
        ClientIps.configureTrustedProxies("10.0.0.0/8, 192.168.0.0/16");
        assertThat(ClientIps.extract(request("10.1.1.1", "203.0.113.5, 192.168.4.4, 10.0.0.9")))
                .isEqualTo("203.0.113.5");
    }

    @Test
    void trustedProxy_wholeChainTrusted_fallsBackToPeer() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", "10.0.0.9, 10.0.0.8")))
                .isEqualTo("10.1.1.1");
    }

    @Test
    void trustedProxy_noHeader_fallsBackToPeer() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", null))).isEqualTo("10.1.1.1");
    }

    /** A peer outside the configured edge gets no say, however plausible its header looks. */
    @Test
    void untrustedPeer_headerIgnoredEvenWhenProxiesAreConfigured() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("198.51.100.7", "203.0.113.5, 10.0.0.9")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void exactAddressEntry_trustsOnlyThatAddress() {
        ClientIps.configureTrustedProxies("10.1.1.1");
        assertThat(ClientIps.extract(request("10.1.1.1", "203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(ClientIps.extract(request("10.1.1.2", "203.0.113.5"))).isEqualTo("10.1.1.2");
    }

    @Test
    void loopbackProxy_isConfigurable() {
        ClientIps.configureTrustedProxies("127.0.0.0/8,::1");
        assertThat(ClientIps.extract(request("127.0.0.1", "203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(ClientIps.extract(request("0:0:0:0:0:0:0:1", "203.0.113.6")))
                .isEqualTo("203.0.113.6");
    }

    @Test
    void ipv6Range_matchesOnPrefixBitsNotBytes() {
        ClientIps.configureTrustedProxies("2001:db8::/34");
        assertThat(ClientIps.extract(request("2001:db8:2000::1", "203.0.113.5")))
                .isEqualTo("203.0.113.5");
        assertThat(ClientIps.extract(request("2001:db8:8000::1", "203.0.113.5")))
                .isEqualTo("2001:db8:8000::1");
    }

    @Test
    void ipv4RangeNeverMatchesAnIpv6Peer() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("2001:db8::1", "203.0.113.5")))
                .isEqualTo("2001:db8::1");
    }

    @Test
    void portSuffixOnAProxyAddressStillMatchesTheRange() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1:41234", "203.0.113.5")))
                .isEqualTo("203.0.113.5");
    }

    // ---- hostile header content -------------------------------------------------------------------

    /** A hop that is not a literal address must not be resolved, and must not be trusted. */
    @Test
    void nonNumericHopIsReturnedVerbatimButNeverTrusted() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", "1.1.1.1, localhost")))
                .isEqualTo("localhost");
    }

    @Test
    void emptyHopsAreSkipped() {
        ClientIps.configureTrustedProxies("10.0.0.0/8");
        assertThat(ClientIps.extract(request("10.1.1.1", "203.0.113.5, , "))).isEqualTo("203.0.113.5");
    }

    // ---- configuration parsing --------------------------------------------------------------------

    @Test
    void unparseableEntriesAreDroppedNotTrusted() {
        ClientIps.configureTrustedProxies("not-an-ip, 10.0.0.0/999, 10.0.0.0/8, ");
        assertThat(ClientIps.trustedProxies()).containsExactly("10.0.0.0/8");
    }

    @Test
    void blankSpecTrustsNothing() {
        ClientIps.configureTrustedProxies("   ");
        assertThat(ClientIps.trustedProxies()).isEmpty();
    }

    @Test
    void nullSpecTrustsNothing() {
        ClientIps.configureTrustedProxies(null);
        assertThat(ClientIps.trustedProxies()).isEmpty();
    }
}
