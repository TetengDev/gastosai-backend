package com.teng.app.gastosai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Monetization toggle. While {@code enforce} is false (the default), every feature is unlocked for
 * every user — the entitlement system is wired and observable but inert, so enabling it later is a
 * single config flip rather than a code change.
 */
@ConfigurationProperties(prefix = "gastos.monetization")
public class MonetizationProperties {

    private boolean enforce = false;

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }
}
