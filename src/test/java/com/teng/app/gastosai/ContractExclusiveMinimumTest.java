package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the TEN-416 correction: the five {@code @DecimalMin(value = "0.0", inclusive = false)}
 * request fields must publish {@code exclusiveMinimum}, not a plain {@code minimum}.
 *
 * <p>springdoc 3.1.0 dropped the exclusive flag and serialised those annotations as
 * {@code minimum: 0.0}, which tells a client zero is accepted; the validator has always rejected
 * it. 3.1.1 emits the bound the server actually enforces. Nothing else in the build would notice a
 * later springdoc bump flipping it back — the spec would simply regenerate, stay byte-consistent
 * with itself, and republish the wrong bound under a new contract version.
 *
 * <p>The inclusive assertions are the other half. A regression that turned every numeric bound
 * exclusive would satisfy the first test and break {@code @Min(0)} / {@code @DecimalMin("0.0")}
 * fields, where zero is a legal value.
 */
class ContractExclusiveMinimumTest {

	private static final Path SPEC = Path.of("contract/openapi.json");

	private static JsonNode property(String schema, String field) throws IOException {
		JsonNode spec = new ObjectMapper().readTree(Files.readString(SPEC));
		JsonNode property = spec.path("components").path("schemas").path(schema)
				.path("properties").path(field);
		assertFalse(property.isMissingNode(),
				"contract/openapi.json no longer publishes " + schema + "." + field + ".");
		return property;
	}

	private void assertExclusiveZeroBound(String schema, String field) throws IOException {
		JsonNode property = property(schema, field);

		assertTrue(property.has("exclusiveMinimum"),
				schema + "." + field + " is annotated @DecimalMin(value = \"0.0\", inclusive = false) "
						+ "but publishes " + property + ". A spec that says minimum: 0.0 promises a zero "
						+ "amount is accepted; the server has always rejected it with a 400.");
		assertEquals(0.0, property.get("exclusiveMinimum").asDouble(),
				"The exclusive bound on " + schema + "." + field + " must still be zero.");
		assertFalse(property.has("minimum"),
				schema + "." + field + " publishes both minimum and exclusiveMinimum; a generator may "
						+ "read either, so the inclusive one must be gone.");
	}

	@Test
	void budgetRequestPublishesExclusiveBounds() throws IOException {
		assertExclusiveZeroBound("BudgetRequest", "amountLimit");
		assertExclusiveZeroBound("BudgetRequest", "exchangeRate");
	}

	@Test
	void budgetRequestV2PublishesAnExclusiveExchangeRateBound() throws IOException {
		assertExclusiveZeroBound("BudgetRequestV2", "exchangeRate");
	}

	@Test
	void expenseRequestPublishesAnExclusiveAmountBound() throws IOException {
		assertExclusiveZeroBound("ExpenseRequest", "amount");
	}

	@Test
	void recurringExpenseRequestPublishesAnExclusiveAmountBound() throws IOException {
		assertExclusiveZeroBound("RecurringExpenseRequest", "amount");
	}

	@Test
	void fieldsThatDoAcceptZeroStillPublishAnInclusiveMinimum() throws IOException {
		for (String[] inclusive : new String[][] {
				{ "GoalRequest", "savedAmount" },
				{ "GoalRequestV2", "savedAmount" },
				{ "BudgetRuleRequest", "monthlyIncome" },
				{ "BudgetRuleRequestV2", "monthlyIncome" } }) {
			JsonNode property = property(inclusive[0], inclusive[1]);

			assertTrue(property.has("minimum"),
					inclusive[0] + "." + inclusive[1] + " accepts zero, so its bound must stay "
							+ "inclusive; it publishes " + property + ".");
			assertEquals(0.0, property.get("minimum").asDouble(),
					"The inclusive bound on " + inclusive[0] + "." + inclusive[1] + " must be zero.");
			assertFalse(property.has("exclusiveMinimum"),
					inclusive[0] + "." + inclusive[1] + " must not publish an exclusive bound — zero is "
							+ "a value the server accepts.");
		}
	}
}
