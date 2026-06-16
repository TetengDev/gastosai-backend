package com.teng.app.gastosai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	@Value("${cors.allowed-origins:http://localhost:5173}")
	private String[] allowedOrigins;

	private final FeatureAccessInterceptor featureAccessInterceptor;
	private final AiRateLimitInterceptor aiRateLimitInterceptor;
	private final AiKeyContextInterceptor aiKeyContextInterceptor;
	private final ViewAsInterceptor viewAsInterceptor;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				.allowedOriginPatterns(allowedOrigins)
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
				.allowedHeaders("*")
				.maxAge(3600);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(viewAsInterceptor);
		registry.addInterceptor(aiKeyContextInterceptor).addPathPatterns("/ai/**");
		registry.addInterceptor(aiRateLimitInterceptor).addPathPatterns("/ai/**");
		registry.addInterceptor(featureAccessInterceptor);
	}
}
