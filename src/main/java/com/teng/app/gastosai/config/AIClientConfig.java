package com.teng.app.gastosai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import com.teng.app.gastosai.ai.ClaudeExpenseParser;
import com.teng.app.gastosai.ai.ClaudeSqlGenerator;
import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.ai.OpenAiExpenseParser;
import com.teng.app.gastosai.ai.OpenAiSqlGenerator;
import com.teng.app.gastosai.ai.SqlGenerator;

@Configuration
@EnableConfigurationProperties({OpenAiProperties.class, ClaudeProperties.class, AiProviderProperties.class, FeatureProperties.class, JwtProperties.class})
public class AIClientConfig
{

	@Bean
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		return mapper;
	}

	@Bean
	public RestClient openAiRestClient(OpenAiProperties properties) {
		return RestClient.builder()
				.baseUrl("https://api.openai.com")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.requestInterceptor((request, body, execution) -> {
					String key = properties.getApiKey();
					if (key != null && !key.isBlank()) {
						request.getHeaders().setBearerAuth(key);
					}
					return execution.execute(request, body);
				})
				.build();
	}

	@Bean
	public RestClient claudeRestClient(ClaudeProperties properties) {
		return RestClient.builder()
				.baseUrl("https://api.anthropic.com/v1")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader("anthropic-version", "2023-06-01")
				.requestInterceptor((request, body, execution) -> {
					String key = properties.getApiKey();
					if (key != null && !key.isBlank()) {
						request.getHeaders().set("x-api-key", key);
					}
					return execution.execute(request, body);
				})
				.build();
	}

	@Bean
	@Primary
	public SqlGenerator sqlGenerator(AiProviderProperties providerProps, OpenAiSqlGenerator openAiGenerator, ClaudeSqlGenerator claudeGenerator) {
		return "claude".equalsIgnoreCase(providerProps.getProvider()) ? claudeGenerator : openAiGenerator;
	}

	@Bean
	@Primary
	public ExpenseParser expenseParser(AiProviderProperties providerProps, OpenAiExpenseParser openAiParser, ClaudeExpenseParser claudeParser) {
		return "claude".equalsIgnoreCase(providerProps.getProvider()) ? claudeParser : openAiParser;
	}
}
