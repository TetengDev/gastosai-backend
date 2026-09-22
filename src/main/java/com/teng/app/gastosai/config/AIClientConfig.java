package com.teng.app.gastosai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import com.teng.app.gastosai.ai.ClaudeExpenseParser;
import com.teng.app.gastosai.ai.ClaudeSqlGenerator;
import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.ai.OpenAiExpenseParser;
import com.teng.app.gastosai.ai.OpenAiSqlGenerator;
import com.teng.app.gastosai.ai.SqlGenerator;

@Configuration
@EnableConfigurationProperties({OpenAiProperties.class, ClaudeProperties.class, AiProviderProperties.class, AiLanguageProperties.class, AiManagedProperties.class, AiCostProperties.class, FeatureProperties.class, JwtProperties.class, MonetizationProperties.class, CacheProperties.class, CategoryLimitProperties.class, PayMongoProperties.class, PricingProperties.class, AlertProperties.class})
public class AIClientConfig
{

	@Bean
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		return mapper;
	}

	private ClientHttpRequestFactory llmRequestFactory(AiManagedProperties managedProps) {
		Duration timeout = Duration.ofSeconds(Math.max(1, managedProps.getRequestTimeoutSeconds()));
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}

	/**
	 * The base URL is configuration, not a literal, so a local run can be pointed at a dead
	 * endpoint and fail to connect instead of reaching the provider. The default is the real
	 * host, so production is unchanged; see {@code application.properties} for the local value.
	 */
	@Bean
	public RestClient openAiRestClient(OpenAiProperties properties, AiManagedProperties managedProps,
			@Value("${gastos.openai.base-url:https://api.openai.com}") String baseUrl) {
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(llmRequestFactory(managedProps))
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.requestInterceptor((request, body, execution) -> {
					String key = AiKeyContext.openai();
					if (key == null || key.isBlank()) {
						key = properties.getApiKey();
					}
					if (key != null && !key.isBlank()) {
						request.getHeaders().setBearerAuth(key);
					}
					return execution.execute(request, body);
				})
				.build();
	}

	/**
	 * Same rule as {@link #openAiRestClient}: the real host by default, overridable so a local
	 * run cannot call out. The {@code /v1} suffix is part of the value because the callers'
	 * paths ({@code /messages}) are relative to it.
	 */
	@Bean
	public RestClient claudeRestClient(ClaudeProperties properties, AiManagedProperties managedProps,
			@Value("${gastos.claude.base-url:https://api.anthropic.com/v1}") String baseUrl) {
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(llmRequestFactory(managedProps))
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader("anthropic-version", "2023-06-01")
				.requestInterceptor((request, body, execution) -> {
					String key = AiKeyContext.claude();
					if (key == null || key.isBlank()) {
						key = properties.getApiKey();
					}
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
