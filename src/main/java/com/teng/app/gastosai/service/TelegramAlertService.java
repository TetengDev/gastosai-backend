package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.AlertProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Posts operational alerts to a Telegram chat via the Bot API. No-op unless alerting is
 * active (enabled + credentials present). Failures are logged and swallowed — an alert
 * that can't be delivered must never break the scheduled job that raised it.
 */
@Slf4j
@Service
public class TelegramAlertService {

    private final AlertProperties props;
    private final RestClient client;

    @Autowired
    public TelegramAlertService(AlertProperties props) {
        this(props, RestClient.create("https://api.telegram.org"));
    }

    // Lets tests inject a RestClient bound to MockRestServiceServer.
    public TelegramAlertService(AlertProperties props, RestClient client) {
        this.props = props;
        this.client = client;
    }

    public void send(String text) {
        if (!props.isActive()) {
            return;
        }
        try {
            client.post()
                    .uri("/bot{token}/sendMessage", props.getBotToken())
                    .body(Map.of("chat_id", props.getChatId(), "text", text))
                    .retrieve()
                    .toBodilessEntity();
            log.info("telegram_alert_sent");
        } catch (Exception e) {
            log.warn("telegram_alert_failed: {}", e.getMessage());
        }
    }
}
