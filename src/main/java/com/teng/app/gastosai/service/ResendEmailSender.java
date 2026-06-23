package com.teng.app.gastosai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sends email via the Resend HTTP API (https://api.resend.com) over port 443. Used instead of SMTP
 * because PaaS free tiers (e.g. Render) block outbound SMTP ports (25/465/587), which makes
 * JavaMail/Gmail time out. Failures are logged and swallowed (best-effort, no enumeration / no 500).
 */
@Slf4j
public class ResendEmailSender implements EmailSender {

    private final RestClient client;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(String apiKey, String from) {
        this.apiKey = apiKey;
        this.from = from;
        this.client = RestClient.create("https://api.resend.com");
    }

    @Override
    public void sendMagicLink(String toEmail, String link) {
        send(toEmail, "Your GastosAI sign-in link",
                """
                Click the link below to sign in. It expires in 15 minutes and can only be used once.

                %s

                If you did not request this, you can safely ignore this email.""".formatted(link));
    }

    @Override
    public void sendNotification(String toEmail, String subject, String body) {
        send(toEmail, subject, body);
    }

    private void send(String toEmail, String subject, String text) {
        Map<String, Object> payload = Map.of(
                "from", from,
                "to", new String[]{toEmail},
                "subject", subject,
                "text", text);
        try {
            client.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("resend_email_sent to={}", toEmail);
        } catch (Exception e) {
            log.warn("resend_email_failed to={}: {}", toEmail, e.getMessage());
        }
    }
}
