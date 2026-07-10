package com.teng.app.gastosai;

import com.teng.app.gastosai.config.PayMongoProperties;
import com.teng.app.gastosai.service.PayMongoWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PayMongoWebhookVerifierTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"data\":{\"type\":\"checkout_session.payment.paid\"}}";
    private static final String TIMESTAMP = "1700000000";

    private PayMongoWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        PayMongoProperties props = new PayMongoProperties();
        props.setWebhookSecret(SECRET);
        verifier = new PayMongoWebhookVerifier(props);
    }

    @Test
    void validTestSignaturePasses() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY, header)).isTrue();
    }

    @Test
    void validLiveSignaturePasses() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",li=" + sig;
        assertThat(verifier.verify(BODY, header)).isTrue();
    }

    @Test
    void tamperedBodyFails() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY + "tampered", header)).isFalse();
    }

    @Test
    void wrongSecretFails() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, "wrong-secret");
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY, header)).isFalse();
    }

    @Test
    void malformedHeaderNoTimestampFails() {
        assertThat(verifier.verify(BODY, "te=somesig")).isFalse();
    }

    @Test
    void nullHeaderFails() {
        assertThat(verifier.verify(BODY, null)).isFalse();
    }

    @Test
    void blankHeaderFails() {
        assertThat(verifier.verify(BODY, "")).isFalse();
    }

    private static String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
