package com.teng.app.gastosai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class PayMongoRestClientConfig {

    @Bean
    public RestClient payMongoRestClient(PayMongoProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    String credentials = properties.getSecretKey() + ":";
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
                    return execution.execute(request, body);
                })
                .build();
    }
}
