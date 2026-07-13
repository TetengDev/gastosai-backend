package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.ai.cost")
@Getter
@Setter
public class AiCostProperties {

    private double inputPerMtokUsd = 0.15;
    private double outputPerMtokUsd = 0.60;
}
