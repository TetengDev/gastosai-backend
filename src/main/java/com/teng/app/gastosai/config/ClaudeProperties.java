package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.claude")
@Getter
@Setter
public class ClaudeProperties {

	private String apiKey;
	private String model = "claude-3-5-sonnet-20241022";

}
