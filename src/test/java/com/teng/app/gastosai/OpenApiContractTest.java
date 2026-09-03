package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.teng.app.gastosai.config.PublicEndpoints;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.v2.ChatResponseV2;
import com.teng.app.gastosai.dto.v2.ExpenseResponseV2;
import com.teng.app.gastosai.entity.ExpenseSource;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
						"ExpenseDisambiguateItemList", "CategoryResponseList")),
				branches,
				"ChatResponse.result must describe every branch the handler can return.");

		// The set above is a literal, so on its own it only checks the oneOf against itself: a
		// payload the service returns and nobody described stays invisible to it. That is how
		// `list_categories` survived the first two passes. This catches the half of the gap a test
		// can see — a chat payload schema that exists but was never wired into the union. The other
		// half (a handler branch with no schema at all) is not reachable from the spec, and
		// ChatActionService is not this issue's to instrument.
		Set<String> orphaned = new TreeSet<>();
		schemas.fieldNames().forEachRemaining(name -> {
			if ((name.endsWith("ChatResult") || name.endsWith("List")) && !branches.contains(name)) {
				orphaned.add(name);
			}
		});
		assertEquals(Set.of(), orphaned,
				"Chat payload schemas published but unreachable from ChatResponse.result — a client "
						+ "can generate the type and never learn which turn delivers it: " + orphaned);

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
				"ExpenseChatItemList", "CategoryTotalChatItemList", "ExpenseDisambiguateItemList",
				"CategoryResponseList")) {
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
	 * The same guarantee for {@code POST /api/v2/ai/chat} (TEN-308).
	 *
	 * <p>That endpoint returned the v1 object verbatim, so it was both undescribed —
	 * {@code ChatResponseV2} did not exist as a schema — and the one v2 path still serving decimal
	 * money, inside {@code result}. The centavos guard below could not see it, because a guard that
	 * looks at schemas cannot fail on a schema that was never published.
	 *
	 * <p>Payloads with no money field stay the v1 schema rather than gaining an identical twin, so
	 * the union below deliberately mixes the two.
	 */
	@Test
	void specPublishesEveryV2ChatResultPayload() throws Exception {
		JsonNode spec = new ObjectMapper().readTree(apiDocs());
		JsonNode schemas = spec.path("components").path("schemas");

		Set<String> expected = new TreeSet<>(Set.of(
				"ChatResponseV2",
				"BudgetChatItemV2", "BudgetSummaryChatResultV2",
				"RecurringChatItemV2", "UpcomingBillChatItemV2", "RecurringChatResultV2",
				"GoalChatItemV2", "ExpenseChatItemV2", "CategoryTotalChatItemV2",
				"MonthlyReportChatResultV2", "ExpenseDisambiguateItemV2"));

		Set<String> missing = new TreeSet<>(expected);
		schemas.fieldNames().forEachRemaining(missing::remove);
		assertTrue(missing.isEmpty(),
				"v2 chat payloads absent from the published contract, so a client generating from "
						+ "the version it was told to migrate to gets no type for them: " + missing);

		Set<String> branches = new TreeSet<>();
		schemas.path("ChatResponseV2").path("properties").path("result").path("oneOf")
				.forEach(member -> branches.add(
						member.path("$ref").asText("").replace("#/components/schemas/", "")));

		assertEquals(
				new TreeSet<>(Set.of(
						"ChatPreviewData", "BudgetSummaryChatResultV2", "RecurringChatResultV2",
						"MonthlyReportChatResultV2", "SubscriptionChatResult", "BulkDeleteChatResult",
						"RecategorizeChatResult", "GoalChatItemListV2", "AlertChatItemList",
						"ExpenseChatItemListV2", "CategoryTotalChatItemListV2",
						"ExpenseDisambiguateItemListV2", "CategoryResponseList",
						// The rows a write turn echoes. v1 leaves these out of its oneOf and names them
						// in prose, which is not the same claim: a schema published elsewhere in the
						// spec is not reachable from this property, so a client generating from it gets
						// no type for the row its own create turn returned.
						"ExpenseResponseV2", "BudgetResponseV2", "GoalResponseV2",
						"RecurringExpenseResponseV2", "CategoryResponse", "UserProfileResponse")),
				branches,
				"ChatResponseV2.result must describe every branch the handler can return, and must "
						+ "point at the centavos twin wherever one exists.");

		// Same array-ness trap as v1: springdoc publishes a named list type as an object bean unless
		// the @Schema(type = "array") on it says otherwise.
		for (String listSchema : Set.of("GoalChatItemListV2", "ExpenseChatItemListV2",
				"CategoryTotalChatItemListV2", "ExpenseDisambiguateItemListV2")) {
			JsonNode resolved = schemas.path(listSchema);
			assertEquals("array", resolved.path("type").asText(""),
					listSchema + " must be published as a JSON array — the wire returns a bare array.");
			assertTrue(resolved.path("items").has("$ref"),
					listSchema + " must name its item schema, or a client generates `unknown[]`.");
			assertTrue(resolved.path("properties").isMissingNode(),
					listSchema + " leaked list bean properties into the contract: "
							+ resolved.path("properties"));
		}

		// The union is discriminated by the same explicit field v1 uses, so it has to arrive as the
		// same closed set. Published as an open string it generates to `string`, which is how the web
		// copy of the v1 shape acquired a "query" turn that does not exist.
		Set<String> turnKinds = new TreeSet<>();
		schemas.path("ChatResponseV2").path("properties").path("type").path("enum")
				.forEach(value -> turnKinds.add(value.asText()));
		assertEquals(new TreeSet<>(Set.of("text", "action", "preview", "disambiguate")), turnKinds,
				"ChatResponseV2.type must publish exactly the turn kinds the handler emits.");

		// Named one by one rather than left to v2MoneyIsIntegerCentavos: that guard sweeps whatever
		// V2 schemas happen to exist, so a chat payload dropped from the spec would make it pass by
		// checking less. These are the fields the issue is about.
		Map<String, Set<String>> money = Map.ofEntries(
				Map.entry("ExpenseChatItemV2", Set.of("amount")),
				Map.entry("CategoryTotalChatItemV2", Set.of("total")),
				Map.entry("BudgetSummaryChatResultV2", Set.of("totalBudgeted", "totalSpent", "safeToSpend")),
				Map.entry("BudgetChatItemV2", Set.of("budgeted", "spent", "remaining")),
				Map.entry("GoalChatItemV2", Set.of("targetAmount", "savedAmount")),
				Map.entry("MonthlyReportChatResultV2", Set.of("totalSpent")),
				Map.entry("RecurringChatItemV2", Set.of("amount")),
				Map.entry("UpcomingBillChatItemV2", Set.of("amount")),
				Map.entry("ExpenseDisambiguateItemV2", Set.of("amount")),
				// The write-turn branches. Their own endpoints cover these fields too, but a branch of
				// this union is reached through this turn, so the guard covers it here as well.
				Map.entry("ExpenseResponseV2", Set.of("amount", "amountInBaseCurrency")),
				Map.entry("BudgetResponseV2", Set.of("amountLimit", "amountLimitInBaseCurrency")),
				Map.entry("GoalResponseV2", Set.of("targetAmount", "savedAmount")),
				Map.entry("RecurringExpenseResponseV2", Set.of("amount")));

		Set<String> offenders = new TreeSet<>();
		money.forEach((schema, properties) -> properties.forEach(property -> {
			JsonNode resolved = schemas.path(schema).path("properties").path(property);
			if (!resolved.path("type").asText("").equals("integer")) {
				offenders.add(schema + "." + property + " (" + resolved.path("type").asText("absent") + ")");
			}
		}));
		assertTrue(offenders.isEmpty(),
				"Every money field on a v2 chat payload must be an integer number of centavos. "
						+ "Offending fields: " + offenders);
	}

	/**
	 * The published shape and the served one, checked against each other (TEN-308).
	 *
	 * <p>Everything above reads the spec, and the spec is derived from the record — so on its own it
	 * would pass just as happily if {@code /api/v2/ai/chat} kept returning the v1 body and merely
	 * described a centavos one. This asserts the conversion itself, on a payload built the way
	 * {@code ChatActionService} builds it: an untyped map.
	 *
	 * <p>The preview case is the one that bites. Its {@code params} are echoed back to
	 * {@code POST /ai/chat/confirm}, which has no v2 twin, so a converted amount there would be
	 * confirmed a hundredfold.
	 */
	@Test
	void v2ChatRestatesResultMoneyAsCentavos() {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("month", "2026-08");
		summary.put("totalBudgeted", new BigDecimal("25000.0000"));
		summary.put("totalSpent", new BigDecimal("18740.2500"));
		summary.put("items", List.of(Map.of("categoryName", "Groceries",
				"budgeted", new BigDecimal("8000.00"),
				"percentUsed", new BigDecimal("80.26"))));

		ChatResponseV2 converted = ChatResponseV2.from(
				new ChatResponse("action", "Budget summary for 2026-08.", summary, 42L));

		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) converted.result();
		assertEquals(2_500_000L, result.get("totalBudgeted"));
		assertEquals(1_874_025L, result.get("totalSpent"));
		assertEquals("2026-08", result.get("month"));
		assertEquals(42L, converted.conversationId());

		@SuppressWarnings("unchecked")
		Map<String, Object> item = ((List<Map<String, Object>>) result.get("items")).getFirst();
		assertEquals(800_000L, item.get("budgeted"));
		assertEquals(new BigDecimal("80.26"), item.get("percentUsed"),
				"A percentage is not money and must survive the conversion unchanged.");

		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("toolName", "create_expense");
		preview.put("params", Map.of("amount", new BigDecimal("320.00"), "description", "SM"));

		@SuppressWarnings("unchecked")
		Map<String, Object> previewResult = (Map<String, Object>) ChatResponseV2
				.from(new ChatResponse("preview", "Add ₱320.00?", preview)).result();
		@SuppressWarnings("unchecked")
		Map<String, Object> params = (Map<String, Object>) previewResult.get("params");
		assertEquals(new BigDecimal("320.00"), params.get("amount"),
				"Preview params are echoed to the v1 confirm endpoint and must not be converted.");

		// A write turn echoes the row as a resource DTO, not a map — the one branch the conversion
		// treats differently from everything above, and the one a client is most likely to read back.
		ExpenseResponse written = new ExpenseResponse(1204L, new BigDecimal("320.0000"), "Groceries",
				null, "SM Supermarket", "EXPENSE", false, "PHP", null, null, ExpenseSource.QUICK_ADD);
		Object echoed = ChatResponseV2.from(
				new ChatResponse("action", "Expense created: ₱320.00 — SM Supermarket", written)).result();
		assertEquals(new ExpenseResponseV2(1204L, 32_000L, "Groceries", null, "SM Supermarket",
						"EXPENSE", false, "PHP", null, null, ExpenseSource.QUICK_ADD),
				echoed,
				"A write turn must echo the row as its v2 twin, or the create path serves decimals.");

		// And the array branches, which arrive as a bare list rather than an object.
		Object candidates = ChatResponseV2.from(new ChatResponse("disambiguate", "Which one?",
				List.of(Map.of("id", 1204L, "amount", new BigDecimal("320.0000"), "date", "2026-08-14")))).result();
		assertEquals(List.of(Map.of("id", 1204L, "amount", 32_000L, "date", "2026-08-14")), candidates,
				"An array payload's items must be converted item by item.");
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
