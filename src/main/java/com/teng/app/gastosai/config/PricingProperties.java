package com.teng.app.gastosai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.pricing")
public class PricingProperties {

    private int premiumMonthlyCentavos = 14900;
    private int premiumAnnualCentavos = 129000;
    private String currency = "PHP";

    public int getPremiumMonthlyCentavos() { return premiumMonthlyCentavos; }
    public void setPremiumMonthlyCentavos(int premiumMonthlyCentavos) { this.premiumMonthlyCentavos = premiumMonthlyCentavos; }

    public int getPremiumAnnualCentavos() { return premiumAnnualCentavos; }
    public void setPremiumAnnualCentavos(int premiumAnnualCentavos) { this.premiumAnnualCentavos = premiumAnnualCentavos; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
