package com.teng.app.gastosai.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the address an IP-keyed abuse control should be keyed on.
 *
 * <p>Every IP-keyed control in the app reads through {@link #extract}: the per-minute public
 * limiter ({@link PublicRateLimitInterceptor}), the registration abuse guard and the daily
 * registration cap. So whatever this returns is an identity, and an identity the caller can choose
 * is not one. Until TEN-425 this took the last hop of {@code X-Forwarded-For} whenever the header
 * was present, which is the correct read behind a reverse proxy that appends the peer it saw and a
 * complete bypass without one: the 2026-09-24 pentest (finding H1) drove {@code POST /auth/login}
 * fourteen times past a limit of ten with a rotating header value and collected zero 429s, and
 * created an account past an exhausted daily registration cap the same way.
 *
 * <p>So the header is only believed when the transport peer is a proxy we put there. Configure the
 * edge with {@code gastos.security.trusted-proxies} (see {@link WebConfig}); with nothing
 * configured — the default, and the shape of the directly-exposed local stack — the header is
 * ignored entirely and the key is {@link HttpServletRequest#getRemoteAddr()}, which a client cannot
 * forge without forging the TCP connection.
 *
 * <p>A trusted peer's header is walked right to left, skipping entries that are themselves trusted
 * proxies, so a chain of known hops still resolves to the address the outermost one saw. If every
 * entry is a trusted proxy, or the header is absent or unparseable, the peer address is used.
 */
public final class ClientIps {

    /**
     * Trusted proxy ranges, replaced wholesale at startup.
     *
     * <p>Static because {@code extract} is called from static context in a controller and an
     * interceptor, and volatile because the write happens on the thread that builds the context
     * while the reads happen on request threads.
     */
    private static volatile List<CidrRange> trustedProxies = List.of();

    private ClientIps() {}

    /**
     * Installs the trusted proxy set. Called once at startup from {@link WebConfig}; visible for
     * tests, which must restore the previous value so a configured range cannot leak between them.
     *
     * @param spec comma-separated addresses or CIDR blocks ({@code 10.0.0.0/8}, {@code ::1/128}, a
     *             bare {@code 203.0.113.9} meaning that address alone). Blank means trust nothing.
     *             Entries that do not parse are dropped rather than trusted.
     */
    public static void configureTrustedProxies(String spec) {
        List<CidrRange> parsed = new ArrayList<>();
        if (spec != null) {
            for (String entry : spec.split(",")) {
                CidrRange range = CidrRange.parse(entry.trim());
                if (range != null) {
                    parsed.add(range);
                }
            }
        }
        trustedProxies = List.copyOf(parsed);
    }

    /** The configured trusted proxy specs, for startup logging and tests. */
    public static List<String> trustedProxies() {
        return trustedProxies.stream().map(CidrRange::spec).toList();
    }

    public static String extract(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrustedProxy(peer)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return peer;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty() || isTrustedProxy(hop)) {
                continue;
            }
            return hop;
        }
        return peer;
    }

    private static boolean isTrustedProxy(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        byte[] candidate = addressBytes(address);
        if (candidate == null) {
            return false;
        }
        for (CidrRange range : trustedProxies) {
            if (range.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a literal address to its bytes, never resolving a name.
     *
     * <p>{@code X-Forwarded-For} is attacker-controlled text, so a hop that is not a literal
     * address must not become a DNS lookup on the request thread.
     */
    private static byte[] addressBytes(String address) {
        String literal = address;
        if (literal.startsWith("[")) {
            int close = literal.indexOf(']');
            if (close < 0) {
                return null;
            }
            literal = literal.substring(1, close);
        } else {
            // A bare "1.2.3.4:5678" carries the source port some proxies append; IPv6 literals use
            // colons of their own, so only strip when there is exactly one.
            int colon = literal.indexOf(':');
            if (colon >= 0 && literal.indexOf(':', colon + 1) < 0) {
                literal = literal.substring(0, colon);
            }
        }
        int percent = literal.indexOf('%'); // scope id on a link-local IPv6 address
        if (percent >= 0) {
            literal = literal.substring(0, percent);
        }
        if (literal.isEmpty() || !isNumericAddress(literal)) {
            return null;
        }
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isNumericAddress(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || c == '.' || c == ':'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** One trusted entry: the network bytes plus how many leading bits of them must match. */
    private record CidrRange(String spec, byte[] network, int prefixBits) {

        static CidrRange parse(String entry) {
            if (entry == null || entry.isBlank()) {
                return null;
            }
            String literal = entry;
            int prefixBits = -1;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                literal = entry.substring(0, slash);
                try {
                    prefixBits = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            byte[] network = addressBytes(literal.trim());
            if (network == null) {
                return null;
            }
            int bits = network.length * 8;
            if (prefixBits < 0) {
                prefixBits = bits; // a bare address is a /32 or /128
            }
            if (prefixBits > bits) {
                return null;
            }
            return new CidrRange(entry, network, prefixBits);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false; // never mix an IPv4 range with an IPv6 candidate
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
