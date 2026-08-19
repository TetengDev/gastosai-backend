package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.PayMongoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayMongoWebhookVerifier {

    // PayMongo's recommended replay window: reject events whose signed timestamp is
    // more than 5 minutes from now.
    private static final long TOLERANCE_SECONDS = 300;

    private final PayMongoProperties properties;

    /**
     * Rejection reasons. Each names one failure path so a WARN line is enough to tell a
     * misconfigured secret from a forged signature from a replayed event, without a debugger.
     */
    enum Reason {
        MISSING_HEADER,
        SECRET_NOT_CONFIGURED,
        NO_TIMESTAMP,
        TIMESTAMP_NOT_NUMERIC,
        TIMESTAMP_OUT_OF_TOLERANCE,
        NO_SIGNATURE_VALUE,
        HMAC_UNAVAILABLE,
        SIGNATURE_MISMATCH
    }

    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            reject(Reason.MISSING_HEADER, rawBody, "header absent or blank");
            return false;
        }
        // Fail closed if the webhook secret is unset — otherwise an empty-key HMAC would be
        // forgeable by anyone who knows the secret is unconfigured.
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            reject(Reason.SECRET_NOT_CONFIGURED, rawBody,
                    "gastos.paymongo.webhook-secret is unset; every webhook will be rejected");
            return false;
        }

        String timestamp = null;
        String testSig = null;
        String liveSig = null;

        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            switch (k) {
                case "t" -> timestamp = v;
                case "te" -> testSig = v;
                case "li" -> liveSig = v;
            }
        }

        if (timestamp == null) {
            reject(Reason.NO_TIMESTAMP, rawBody, "no t= element; " + describe(null, testSig, liveSig));
            return false;
        }

        // Reject stale/future timestamps to bound replay of a captured event.
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(Reason.TIMESTAMP_NOT_NUMERIC, rawBody,
                    "t= is not an epoch-second integer; " + describe(timestamp, testSig, liveSig));
            return false;
        }
        long skewSeconds = Instant.now().getEpochSecond() - ts;
        if (Math.abs(skewSeconds) > TOLERANCE_SECONDS) {
            reject(Reason.TIMESTAMP_OUT_OF_TOLERANCE, rawBody,
                    "signed timestamp is " + skewSeconds + "s from now, tolerance is ±"
                            + TOLERANCE_SECONDS + "s; " + describe(timestamp, testSig, liveSig));
            return false;
        }

        if (isBlank(testSig) && isBlank(liveSig)) {
            reject(Reason.NO_SIGNATURE_VALUE, rawBody,
                    "neither te= nor li= carried a value; " + describe(timestamp, testSig, liveSig));
            return false;
        }

        String payload = timestamp + "." + rawBody;
        String computed = hmacSha256Hex(payload, secret);
        if (computed == null) {
            reject(Reason.HMAC_UNAVAILABLE, rawBody, "HmacSHA256 unavailable in this JVM");
            return false;
        }

        byte[] computedBytes = computed.getBytes(StandardCharsets.UTF_8);

        if (!isBlank(liveSig)) {
            if (MessageDigest.isEqual(computedBytes, liveSig.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        if (!isBlank(testSig)) {
            if (MessageDigest.isEqual(computedBytes, testSig.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }

        reject(Reason.SIGNATURE_MISMATCH, rawBody,
                "computed digest matched no supplied signature — wrong secret or altered body; "
                        + describe(timestamp, testSig, liveSig));
        return false;
    }

    /**
     * Logs one rejection. Deliberately carries no secret material: never the webhook secret, never
     * the computed digest (which is an oracle for forging), and never the request body. Only the
     * signed timestamp, the body's length, and which header elements were present — enough to line
     * the event up against the PayMongo dashboard.
     */
    private void reject(Reason reason, String rawBody, String detail) {
        log.warn("PayMongo webhook signature rejected: reason={} bodyLength={} — {}",
                reason, rawBody == null ? 0 : rawBody.length(), detail);
    }

    /** Describes which signature elements arrived, by presence only — never their values. */
    private static String describe(String timestamp, String testSig, String liveSig) {
        List<String> present = new ArrayList<>();
        if (timestamp != null) present.add("t");
        if (!isBlank(testSig)) present.add("te");
        if (!isBlank(liveSig)) present.add("li");
        return "t=" + (timestamp == null ? "<none>" : timestamp) + " elements=" + present;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }
}
