package com.teng.app.gastosai.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declares the bearer-JWT scheme in the generated spec and attaches it to the operations that
 * actually require it.
 *
 * <p>springdoc infers nothing from the Spring Security filter chain, so before this existed the
 * published contract described every protected endpoint as though it were open: no
 * {@code components.securitySchemes}, no {@code security} on any operation. That made Swagger UI
 * unusable (no Authorize control to attach a token with) and, more importantly, made
 * {@code @tetengdev/gastosai-api-contract} wrong — anything generated from it omitted the
 * Authorization header and got a 401 the spec gave no reason to expect.
 *
 * <p>The requirement is applied per operation rather than globally so the genuinely anonymous
 * endpoints — login, register, the PayMongo webhook — stay honest. {@link PublicEndpoints} is the
 * single source of that list, shared with {@link SecurityConfig}.
 */
@Configuration
public class OpenApiConfig {

	static final String BEARER_SCHEME = "bearerAuth";

	@Bean
	public OpenApiCustomizer bearerAuthCustomizer() {
		return openApi -> {
			openApi.schemaRequirement(BEARER_SCHEME, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("Backend-issued JWT from POST /auth/login, sent as `Authorization: Bearer <token>`."));

			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((path, pathItem) ->
					operationsByMethod(pathItem).forEach((method, operation) -> {
						if (!PublicEndpoints.isPublic(method, path)) {
							operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
						}
					}));
		};
	}

	/**
	 * The operations declared on a path, keyed by HTTP method.
	 *
	 * <p>{@code PathItem.readOperationsMap()} returns swagger's own {@code HttpMethod} enum, which
	 * {@link PublicEndpoints} cannot match against; this maps it onto Spring's.
	 */
	private static Map<HttpMethod, Operation> operationsByMethod(PathItem pathItem) {
		Map<HttpMethod, Operation> operations = new LinkedHashMap<>();
		pathItem.readOperationsMap().forEach((method, operation) ->
				operations.put(HttpMethod.valueOf(method.name()), operation));
		return operations;
	}
}
