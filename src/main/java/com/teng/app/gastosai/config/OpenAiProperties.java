package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.openai")
@Getter
@Setter
public class OpenAiProperties {

	private String apiKey;
	private String model = "gpt-4o-mini";

}
