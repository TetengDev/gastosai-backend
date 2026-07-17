package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Operational alerting config. Disabled by default and a no-op unless {@code enabled=true}
 * AND both Telegram credentials are present, so a deployment without alert config sends nothing.
 */
@ConfigurationProperties(prefix = "gastos.alerts")
@Getter
@Setter
public class AlertProperties {

    private boolean enabled = false;
    private String botToken = "";
    private String chatId = "";
    private long intervalMs = 900_000;
    private BigDecimal dailyCostUsd = new BigDecimal("5.00");
    private int errorRatePerHour = 20;
    private double globalCapWarnFraction = 0.9;

    public boolean isActive() {
        return enabled && !botToken.isBlank() && !chatId.isBlank();
    }
}
