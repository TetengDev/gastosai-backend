package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.teng.app.gastosai.config.PublicEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generates the published API contract and guards its completeness.
 *
 * <p>This repo owns {@code @tetengdev/gastosai-api-contract} (see CONTRACT.md): the spec here is
 * what every client pins and generates its typed client from. Writing it from a test rather than
 * by booting a separate process keeps generation on the same path as the rest of the suite — no
 * port binding, no JMX handshake, identical on a developer machine and a CI runner.
 *
 * <p>The spec is derived from controller and DTO signatures, so the test datasource is irrelevant
 * to its content.
 *
 * <p>A surface change shows up as a diff in {@code contract/openapi.json}. That diff is the signal
 * to publish a new contract version — minor if additive, major plus a new {@code /api/v2} path if
 * breaking.
 */
@SpringBootTest
class OpenApiContractTest {

	private static final Path CONTRACT_SPEC = Path.of("contract", "openapi.json");

	/** The path-item keys that are operations; everything else on a path item is metadata. */
	private static final Set<String> OPERATION_KEYS = Set.of(
			"get", "put", "post", "delete", "options", "head", "patch", "trace");

	private static final Pattern MONEY_PROPERTY = Pattern.compile(
			"(?i)amount|total|budget|spent|price|cost|balance|saved|target(?!Date)");

	@Autowired
	WebApplicationContext webApplicationContext;

	// Qualified: actuator contributes a second RequestMappingHandlerMapping
	// (controllerEndpointHandlerMapping) and only the MVC one describes application endpoints.
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@Test
	void generatesSpecCoveringEveryController() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		ObjectMapper mapper = new ObjectMapper();
		JsonNode spec = mapper.readTree(body);
		JsonNode paths = spec.get("paths");

		assertTrue(paths != null && !paths.isEmpty(),
				"OpenAPI spec documents no paths — springdoc produced an empty surface.");

		Set<String> documented = new TreeSet<>();
		paths.fieldNames().forEachRemaining(documented::add);

		// Every @RestController must contribute at least one documented path. A controller that
		// springdoc silently skips would ship a contract clients cannot call.
		Set<String> uncovered = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			Class<?> controller = handler.getBeanType();
			if (!controller.isAnnotationPresent(RestController.class)) {
				continue;
			}
			if (!controller.getPackageName().startsWith("com.teng.app.gastosai")) {
				continue;
			}
			boolean covered = entry.getKey().getPatternValues().stream().anyMatch(documented::contains);
			if (!covered) {
				uncovered.add(controller.getSimpleName() + " " + entry.getKey().getPatternValues());
			}
		}
		assertTrue(uncovered.isEmpty(), "Endpoints missing from the OpenAPI spec: " + uncovered);

		// Sorted keys + stable indentation so an unrelated rebuild produces a byte-identical file
		// and `git diff contract/openapi.json` only ever reflects a real surface change.
		ObjectMapper writer = new ObjectMapper();
		writer.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		writer.configure(SerializationFeature.INDENT_OUTPUT, true);
		String rendered = writer.writeValueAsString(writer.treeToValue(spec, Object.class)) + "\n";

		Files.createDirectories(CONTRACT_SPEC.getParent());
		Files.writeString(CONTRACT_SPEC, rendered);
	}

	@Test
	void specLeavesExactlyThePublicOperationsUnsecured() throws Exception {
		// The published contract's `security` blocks are the only thing a generated client knows
		// about authentication, so an operation the server protects but the spec leaves unsecured is
		// a 401 the client had no reason to expect — and the reverse advertises a token requirement
		// that does not exist. OpenApiConfig derives those blocks from PublicEndpoints.isPublic; this
		// asserts the derivation actually landed, across the whole documented surface.
		//
		// Stated as set equality rather than a count on purpose: an endpoint added to the security
		// list without the contract (or the other way round) fails here by name, and no one has to
		// remember to update a number.
		JsonNode paths = new ObjectMapper().readTree(apiDocs()).get("paths");
		assertTrue(paths != null && !paths.isEmpty(), "OpenAPI spec documents no paths.");

		Set<String> unsecuredInSpec = new TreeSet<>();
		Set<String> publicByRule = new TreeSet<>();

		paths.fields().forEachRemaining(pathEntry -> {
			String path = pathEntry.getKey();
			pathEntry.getValue().fields().forEachRemaining(opEntry -> {
				HttpMethod method = asHttpMethod(opEntry.getKey());
				if (method == null) {
					// `parameters`, `summary` and friends sit beside the operations on a path item.
					return;
				}
				String operation = method + " " + path;
				JsonNode security = opEntry.getValue().get("security");
				if (security == null || security.isEmpty()) {
					unsecuredInSpec.add(operation);
				}
				if (PublicEndpoints.isPublic(method, path)) {
					publicByRule.add(operation);
				}
			});
		});

		assertEquals(publicByRule, unsecuredInSpec,
				"The spec's unsecured operations must be exactly PublicEndpoints.isPublic applied to "
						+ "the documented paths. A difference means the contract and the filter chain "
						+ "disagree about who may call what.");
	}

	/** Spring's {@link HttpMethod} for an OpenAPI path-item key, or {@code null} if it is not one. */
	private static HttpMethod asHttpMethod(String pathItemKey) {
		return OPERATION_KEYS.contains(pathItemKey) ? HttpMethod.valueOf(pathItemKey.toUpperCase()) : null;
	}

	private String apiDocs() throws Exception {
		return MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build()
				.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	@Test
	void specDeclaresNoFloatingPointMoney() throws Exception {
		// Guardrail for the money invariant in CONTRACT.md. The backend serves money as BigDecimal
		// (see KNOWN-GAPS.md — the contract calls for integer centavos, which is a separate breaking
		// change), and springdoc renders BigDecimal as "number" with NO format, i.e. an
		// arbitrary-precision JSON number. Switching any of these to double/float would bake
		// precision loss into every generated client, silently.
		//
		// Scoped to money-bearing names on purpose: non-monetary doubles such as a percentage are
		// legitimate, so a blanket "no double anywhere" rule would be noise.
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode schemas = new ObjectMapper().readTree(body).path("components").path("schemas");

		Set<String> offenders = new TreeSet<>();
		schemas.fields().forEachRemaining(schema -> {
			JsonNode properties = schema.getValue().path("properties");
			properties.fields().forEachRemaining(property -> {
				if (!MONEY_PROPERTY.matcher(property.getKey()).find()) {
					return;
				}
				String format = property.getValue().path("format").asText("");
				if (format.equals("float") || format.equals("double")) {
					offenders.add(schema.getKey() + "." + property.getKey() + " (" + format + ")");
				}
			});
		});

		assertTrue(offenders.isEmpty(),
				"Money must never be a floating-point type — see CONTRACT.md. Offending fields: " + offenders);
	}
}
