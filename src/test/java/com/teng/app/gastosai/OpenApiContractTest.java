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

	/**
	 * Every payload {@code POST /ai/chat} can put in {@code ChatResponse.result} is published
	 * (TEN-275).
	 *
	 * <p>These shapes were served for months and described nowhere, so both clients hand-wrote them
	 * and drifted — the web copy carried a {@code "query"} turn type the backend has never emitted.
	 * {@code result} is typed {@code Object} in Java (the service builds maps), so nothing but this
	 * test stops the schemas from silently falling out of the spec again: delete the {@code oneOf}
	 * on {@link com.teng.app.gastosai.dto.ChatResponse} and every assertion below still compiles.
	 *
	 * <p>Scoped to v1. {@code /api/v2/ai/chat} delegates to the same handler and returns the same
	 * object, so it shares this schema; the integer-centavos twins for it are a separate change.
	 */
	@Test
	void specPublishesEveryChatResultPayload() throws Exception {
		JsonNode spec = new ObjectMapper().readTree(apiDocs());
		JsonNode schemas = spec.path("components").path("schemas");

		Set<String> expected = new TreeSet<>(Set.of(
				"ChatPreviewData",
				"GoalChatItem", "BudgetChatItem", "BudgetSummaryChatResult",
				"RecurringChatItem", "UpcomingBillChatItem", "RecurringChatResult",
				"AlertChatItem", "ExpenseChatItem", "CategoryTotalChatItem",
				"MonthlyReportChatResult",
				// Not in the issue's list of eleven, but the acceptance criterion is *every* payload
				// and review found these four still undescribed: the entitlement view, the two bulk
				// counters, and the delete-expense disambiguation candidate.
				"SubscriptionChatResult", "BulkDeleteChatResult", "RecategorizeChatResult",
				"ExpenseDisambiguateItem"));

		Set<String> missing = new TreeSet<>(expected);
		schemas.fieldNames().forEachRemaining(missing::remove);
		assertTrue(missing.isEmpty(),
				"Chat result payloads absent from the published contract, so `npm run gen:api` "
						+ "generates nothing for them and clients must hand-type them again: " + missing);

		// The union itself, not just the members: a schema published but unreachable from
		// ChatResponse.result tells a client the shape exists without saying when it arrives.
		Set<String> branches = new TreeSet<>();
		schemas.path("ChatResponse").path("properties").path("result").path("oneOf")
				.forEach(member -> branches.add(
						member.path("$ref").asText("").replace("#/components/schemas/", "")));

		assertEquals(
				new TreeSet<>(Set.of(
						"ChatPreviewData", "BudgetSummaryChatResult", "RecurringChatResult",
						"MonthlyReportChatResult", "SubscriptionChatResult", "BulkDeleteChatResult",
						"RecategorizeChatResult", "GoalChatItemList", "AlertChatItemList",
						"ExpenseChatItemList", "CategoryTotalChatItemList",
						"ExpenseDisambiguateItemList")),
				branches,
				"ChatResponse.result must describe every branch the handler can return.");

		// The delete-expense disambiguation is not an ExpenseChatItem: it carries no `category` and
		// its amount is unrounded. Publishing one where the other is served would put a required
		// property in the generated type that the response never contains.
		assertTrue(schemas.path("ExpenseDisambiguateItem").path("properties").path("category")
						.isMissingNode(),
				"ExpenseDisambiguateItem must not claim a `category` — that branch does not send one.");

		// Four branches are bare JSON arrays. springdoc resolves a named list type as an object bean
		// unless it is told otherwise, and did exactly that on the first attempt here — publishing
		// `{type: object, properties: {empty, first, last}}` for an array. That contract would compile
		// on the client and fail on the first response, so assert the array-ness directly.
		for (String listSchema : Set.of("GoalChatItemList", "AlertChatItemList",
				"ExpenseChatItemList", "CategoryTotalChatItemList", "ExpenseDisambiguateItemList")) {
			JsonNode resolved = schemas.path(listSchema);
			assertEquals("array", resolved.path("type").asText(""),
					listSchema + " must be published as a JSON array — the wire returns a bare array.");
			assertTrue(resolved.path("items").has("$ref"),
					listSchema + " must name its item schema, or a client generates `unknown[]`.");
			assertTrue(resolved.path("properties").isMissingNode(),
					listSchema + " leaked list bean properties into the contract: "
							+ resolved.path("properties"));
		}

		// The turn kind is the one field a client may branch on before reading `result`, so it has to
		// arrive as a closed set. Published as an open string it generates to `string`, which is how
		// the web copy acquired a "query" turn that does not exist.
		Set<String> turnKinds = new TreeSet<>();
		schemas.path("ChatResponse").path("properties").path("type").path("enum")
				.forEach(value -> turnKinds.add(value.asText()));
		assertEquals(new TreeSet<>(Set.of("text", "action", "preview", "disambiguate")), turnKinds,
				"ChatResponse.type must publish exactly the turn kinds the handler emits.");
	}

	/**
	 * The v2 surface's entire reason to exist: money as an integer number of centavos (TEN-135).
	 *
	 * <p>The float/double guard above passes trivially for an integer, so it cannot tell a v2 that
	 * serves centavos from one that quietly went on serving decimals — and a v2 that serves
	 * decimals is a major version published for nothing, with both clients pinned to it. This is
	 * the assertion that makes the promise checkable.
	 *
	 * <p>Percentages are excluded by name. {@code percentOfMonthTotal} matches a money-ish pattern
	 * on "Total" while being a legitimate non-integer, and a guard that has to be argued with is a
	 * guard that gets deleted.
	 */
	@Test
	void v2MoneyIsIntegerCentavos() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode schemas = new ObjectMapper().readTree(body).path("components").path("schemas");

		Set<String> checked = new TreeSet<>();
		Set<String> offenders = new TreeSet<>();
		schemas.fields().forEachRemaining(schema -> {
			if (!schema.getKey().endsWith("V2")) {
				return;
			}
			schema.getValue().path("properties").fields().forEachRemaining(property -> {
				String name = property.getKey();
				if (!MONEY_PROPERTY.matcher(name).find() || name.toLowerCase().startsWith("percent")) {
					return;
				}
				checked.add(schema.getKey() + "." + name);
				if (!property.getValue().path("type").asText("").equals("integer")) {
					offenders.add(schema.getKey() + "." + name + " ("
							+ property.getValue().path("type").asText("?") + ")");
				}
			});
		});

		assertTrue(checked.size() >= 20,
				"Expected the v2 surface to carry money fields; found " + checked.size()
						+ ". Has the v2 schema naming changed, leaving this guard checking nothing?");
		assertTrue(offenders.isEmpty(),
				"/api/v2 must serve money as integer centavos (TEN-135). Offending fields: " + offenders);
	}
}
