package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.insights.cache")
@Getter
@Setter
public class CacheProperties {

	private boolean enabled = true;
	private long ttlMinutes = 15;
	private long maxSize = 10000;
}
