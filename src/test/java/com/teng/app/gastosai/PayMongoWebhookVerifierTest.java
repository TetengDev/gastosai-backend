package com.teng.app.gastosai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.teng.app.gastosai.config.PayMongoProperties;
import com.teng.app.gastosai.service.PayMongoWebhookVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayMongoWebhookVerifierTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"data\":{\"type\":\"checkout_session.payment.paid\"}}";
    private static final String TIMESTAMP = String.valueOf(Instant.now().getEpochSecond());

    private PayMongoWebhookVerifier verifier;
    private Logger verifierLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        PayMongoProperties props = new PayMongoProperties();
        props.setWebhookSecret(SECRET);
        verifier = new PayMongoWebhookVerifier(props);

        logs = new ListAppender<>();
        logs.start();
        verifierLogger = (Logger) LoggerFactory.getLogger(PayMongoWebhookVerifier.class);
        verifierLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        verifierLogger.detachAppender(logs);
        logs.stop();
    }

    // --- accepted ---------------------------------------------------------

    @Test
    void validTestSignaturePasses() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY, header)).isTrue();
        // A success must be silent on the rejection channel.
        assertThat(logs.list).isEmpty();
    }

    @Test
    void validLiveSignaturePasses() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",li=" + sig;
        assertThat(verifier.verify(BODY, header)).isTrue();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void timestampAtTheEdgeOfToleranceStillPasses() throws Exception {
        // 300s is the tolerance itself, so the boundary must be inclusive. Held one second inside
        // it so the clock ticking mid-test cannot flip the assertion.
        String edge = String.valueOf(Instant.now().getEpochSecond() - 299);
        String sig = computeHmac(edge + "." + BODY, SECRET);
        assertThat(verifier.verify(BODY, "t=" + edge + ",te=" + sig)).isTrue();
    }

    // --- missing signature ------------------------------------------------

    @Test
    void nullHeaderFails() {
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertRejectedBecause("MISSING_HEADER");
    }

    @Test
    void blankHeaderFails() {
        assertThat(verifier.verify(BODY, "   ")).isFalse();
        assertRejectedBecause("MISSING_HEADER");
    }

    @Test
    void headerCarryingOnlyATimestampFails() {
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP)).isFalse();
        assertRejectedBecause("NO_SIGNATURE_VALUE");
    }

    @Test
    void headerWithEmptySignatureValueFails() {
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP + ",te=")).isFalse();
        assertRejectedBecause("NO_SIGNATURE_VALUE");
    }

    // --- malformed signature ----------------------------------------------

    @Test
    void malformedHeaderNoTimestampFails() {
        assertThat(verifier.verify(BODY, "te=somesig")).isFalse();
        assertRejectedBecause("NO_TIMESTAMP");
    }

    @Test
    void headerWithNoKeyValuePairsAtAllFails() {
        assertThat(verifier.verify(BODY, "garbage")).isFalse();
        assertRejectedBecause("NO_TIMESTAMP");
    }

    @Test
    void nonNumericTimestampFails() {
        assertThat(verifier.verify(BODY, "t=not-a-number,te=deadbeef")).isFalse();
        assertRejectedBecause("TIMESTAMP_NOT_NUMERIC");
    }

    @Test
    void signatureThatIsNotHexFails() {
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP + ",te=zzzz")).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    // --- wrong signature --------------------------------------------------

    @Test
    void tamperedBodyFails() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY + "tampered", header)).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    @Test
    void wrongSecretFails() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, "wrong-secret");
        String header = "t=" + TIMESTAMP + ",te=" + sig;
        assertThat(verifier.verify(BODY, header)).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    @Test
    void signatureSignedWithoutTheTimestampPrefixFails() throws Exception {
        // The timestamp is part of the signed payload; omitting it must not verify, or the
        // replay window could be sidestepped by re-stamping a captured body.
        String sig = computeHmac(BODY, SECRET);
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP + ",te=" + sig)).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    @Test
    void signatureValidForAnotherTimestampFails() throws Exception {
        // Signed for t=A, presented as t=B, both inside the window.
        String other = String.valueOf(Instant.now().getEpochSecond() - 60);
        String sig = computeHmac(other + "." + BODY, SECRET);
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP + ",te=" + sig)).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    @Test
    void wrongLiveSignatureIsNotRescuedByABlankTestSignature() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, "wrong-secret");
        assertThat(verifier.verify(BODY, "t=" + TIMESTAMP + ",li=" + sig + ",te=")).isFalse();
        assertRejectedBecause("SIGNATURE_MISMATCH");
    }

    // --- replay -----------------------------------------------------------

    @Test
    void staleTimestampFails() throws Exception {
        String stale = String.valueOf(Instant.now().getEpochSecond() - 400);
        String sig = computeHmac(stale + "." + BODY, SECRET);
        String header = "t=" + stale + ",te=" + sig;
        assertThat(verifier.verify(BODY, header)).isFalse();
        assertRejectedBecause("TIMESTAMP_OUT_OF_TOLERANCE");
    }

    @Test
    void replayOfAGenuineEventOutsideTheWindowFails() throws Exception {
        // The exact replay case: a header PayMongo really sent, captured and re-sent an hour
        // later. The signature is authentic — only the age rejects it.
        String signedAt = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String authenticSig = computeHmac(signedAt + "." + BODY, SECRET);
        String capturedHeader = "t=" + signedAt + ",li=" + authenticSig;

        assertThat(verifier.verify(BODY, capturedHeader)).isFalse();
        assertRejectedBecause("TIMESTAMP_OUT_OF_TOLERANCE");
        // The reason must say how stale it was, so a replay is distinguishable from clock drift.
        assertThat(onlyLog().getFormattedMessage()).contains("3600s from now");
    }

    @Test
    void timestampFarInTheFutureFails() throws Exception {
        String future = String.valueOf(Instant.now().getEpochSecond() + 3600);
        String sig = computeHmac(future + "." + BODY, SECRET);
        assertThat(verifier.verify(BODY, "t=" + future + ",te=" + sig)).isFalse();
        assertRejectedBecause("TIMESTAMP_OUT_OF_TOLERANCE");
    }

    // --- misconfiguration -------------------------------------------------

    @Test
    void blankSecretFailsClosed() {
        assertThat(verifierWithSecret("").verify(BODY, "t=" + TIMESTAMP + ",te=deadbeef")).isFalse();
        assertRejectedBecause("SECRET_NOT_CONFIGURED");
    }

    @Test
    void nullSecretFailsClosed() {
        assertThat(verifierWithSecret(null).verify(BODY, "t=" + TIMESTAMP + ",te=deadbeef")).isFalse();
        assertRejectedBecause("SECRET_NOT_CONFIGURED");
    }

    @Test
    void unconfiguredSecretRejectsAWellFormedForgery() throws Exception {
        // The attack the fail-closed branch exists for: an environment that shipped without the
        // secret set, and an attacker who signs with a guess. The signature is structurally
        // perfect; the verifier must never reach the comparison at all.
        // (An empty key is not even the danger it looks like — the JCE refuses to construct one,
        // so the exposure of an unset secret is this guessing case, not an empty-key HMAC.)
        String sig = computeHmac(TIMESTAMP + "." + BODY, "guessed-secret");
        assertThat(verifierWithSecret("").verify(BODY, "t=" + TIMESTAMP + ",te=" + sig)).isFalse();
        assertRejectedBecause("SECRET_NOT_CONFIGURED");
    }

    // --- what the log may and may not carry -------------------------------

    @Test
    void rejectionLogNeverCarriesSecretMaterial() throws Exception {
        String validSig = computeHmac(TIMESTAMP + "." + BODY, SECRET);
        String sensitiveBody = "{\"card\":\"4111111111111111\",\"email\":\"payer@example.com\"}";

        verifier.verify(sensitiveBody, "t=" + TIMESTAMP + ",te=" + validSig);
        verifier.verify(sensitiveBody, "t=" + TIMESTAMP);
        verifier.verify(sensitiveBody, null);
        verifierWithSecret(SECRET).verify(sensitiveBody, "t=1,te=" + validSig);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertThat(line)
                    .as("rejection log must not leak the webhook secret")
                    .doesNotContain(SECRET);
            assertThat(line)
                    .as("rejection log must not leak the expected digest — it is a forging oracle")
                    .doesNotContain(computeHmac(TIMESTAMP + "." + sensitiveBody, SECRET));
            assertThat(line)
                    .as("rejection log must not echo the request body")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("payer@example.com");
        }
    }

    @Test
    void rejectionLogCarriesEnoughToInvestigate() throws Exception {
        String sig = computeHmac(TIMESTAMP + "." + BODY, "wrong-secret");
        verifier.verify(BODY, "t=" + TIMESTAMP + ",li=" + sig);

        String line = onlyLog().getFormattedMessage();
        assertThat(line)
                .contains("reason=SIGNATURE_MISMATCH")
                .contains("bodyLength=" + BODY.length())
                .contains("t=" + TIMESTAMP)
                .contains("elements=[t, li]");
    }

    @Test
    void anOverlongTimestampIsTruncatedBeforeItReachesTheLog() {
        // t= is echoed for correlation, but on this path it has not been through Long.parseLong,
        // so it is arbitrary attacker text. It must not be able to become a kilobyte log line.
        String flood = "9".repeat(4000);
        assertThat(verifier.verify(BODY, "t=" + flood + ",te=deadbeef")).isFalse();

        String line = onlyLog().getFormattedMessage();
        assertRejectedBecause("TIMESTAMP_NOT_NUMERIC");
        assertThat(line).hasSizeLessThan(400);
        assertThat(line).contains("truncated from 4000");
    }

    @Test
    void controlCharactersInTheTimestampCannotRestructureTheLog() {
        // A newline in the logged value would let an attacker forge a second log line.
        assertThat(verifier.verify(BODY, "t=12\n34\r WARN forged,te=deadbeef")).isFalse();

        String line = onlyLog().getFormattedMessage();
        assertRejectedBecause("TIMESTAMP_NOT_NUMERIC");
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aForgedTruncationMarkerCannotSurviveIntoTheLog() {
        // A short value that spells out the marker by hand. If it survived, it would hand whoever
        // is investigating a false size for the incident.
        assertThat(verifier.verify(BODY, "t=…(truncated from 4000),te=deadbeef")).isFalse();

        String line = onlyLog().getFormattedMessage();
        assertRejectedBecause("TIMESTAMP_NOT_NUMERIC");
        // The attacker's ASCII text still shows — that is the value they sent, and echoing it is
        // the point. What they cannot forge is the marker itself: the ellipsis only survives the
        // filter when this class appended it, so the line cannot claim a truncation that never
        // happened.
        assertThat(line)
                .as("only this class may write a truncation marker")
                .doesNotContain("…(truncated from");
        assertThat(line).contains(".(truncated from 4000)");
    }

    @Test
    void rejectionsAreLoggedAtWarn() {
        verifier.verify(BODY, null);
        assertThat(onlyLog().getLevel()).isEqualTo(Level.WARN);
    }

    // --- helpers ----------------------------------------------------------

    private PayMongoWebhookVerifier verifierWithSecret(String secret) {
        PayMongoProperties props = new PayMongoProperties();
        props.setWebhookSecret(secret);
        return new PayMongoWebhookVerifier(props);
    }

    private void assertRejectedBecause(String reason) {
        assertThat(onlyLog().getFormattedMessage())
                .as("expected exactly one rejection logged with reason=%s", reason)
                .contains("reason=" + reason);
    }

    private ILoggingEvent onlyLog() {
        List<ILoggingEvent> events = logs.list;
        assertThat(events).as("expected exactly one log event").hasSize(1);
        return events.getFirst();
    }

    private static String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
