package com.teng.app.gastosai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-plan cap on the number of categories a user may create. A value of {@code 0} means unlimited.
 * Only enforced when {@code gastos.monetization.enforce=true}; admins/PREMIUM are effectively
 * unlimited by default.
 */
@ConfigurationProperties(prefix = "gastos.limits.categories")
public class CategoryLimitProperties {

    private int free = 5;
    private int premium = 0;
    private int trial = 0;

    public int getFree() {
        return free;
    }

    public void setFree(int free) {
        this.free = free;
    }

    public int getPremium() {
        return premium;
    }

    public void setPremium(int premium) {
        this.premium = premium;
    }

    public int getTrial() {
        return trial;
    }

    public void setTrial(int trial) {
        this.trial = trial;
    }
}
