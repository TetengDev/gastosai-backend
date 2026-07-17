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

    /** Master switch. When false the scheduler and sender do nothing. */
    private boolean enabled = false;

    private String botToken = "";
    private String chatId = "";

    /** How often the scheduler evaluates thresholds. */
    private long intervalMs = 900_000; // 15 min

    /** Alert once/day when today's estimated AI spend exceeds this many USD. */
    private BigDecimal dailyCostUsd = new BigDecimal("5.00");

    /** Alert once/hour when server-error events in the last hour exceed this count. */
    private int errorRatePerHour = 20;

    /** Alert once/day when today's AI requests reach this fraction of the global daily cap. */
    private double globalCapWarnFraction = 0.9;

    public boolean isActive() {
        return enabled && !botToken.isBlank() && !chatId.isBlank();
    }
}
