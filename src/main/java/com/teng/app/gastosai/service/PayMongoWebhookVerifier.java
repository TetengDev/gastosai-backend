package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.PayMongoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class PayMongoWebhookVerifier {

    private final PayMongoProperties properties;

    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
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
            return false;
        }

        String payload = timestamp + "." + rawBody;
        String computed = hmacSha256Hex(payload, properties.getWebhookSecret());
        if (computed == null) {
            return false;
        }

        byte[] computedBytes = computed.getBytes(StandardCharsets.UTF_8);

        if (liveSig != null) {
            if (MessageDigest.isEqual(computedBytes, liveSig.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        if (testSig != null) {
            if (MessageDigest.isEqual(computedBytes, testSig.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }

        return false;
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
