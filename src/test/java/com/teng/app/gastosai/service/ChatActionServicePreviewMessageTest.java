package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The preview line (TEN-401) — the sentence a user reads before approving a write.
 *
 * <p>One test per tool the message can describe. The natural-language path builds it for every
 * create tool and for the two destructive ones, which is the whole set: anything else executes
 * without a confirmation step, so no other tool can reach this message.
 */
@ExtendWith(MockitoExtension.class)
class ChatActionServicePreviewMessageTest extends ChatActionServiceUnitTestSupport {

	/** A preview is a proposal: it describes the write and performs none of it. */
	private ChatResponse preview(String toolName, String paramsJson) {
		ChatResponse resp = run(toolName, paramsJson, null, user());
		assertThat(resp.type()).isEqualTo("preview");
		verifyNoInteractions(expenseService, budgetService, savingsGoalService,
				recurringExpenseService, categoryService, expenseRepository);
		return resp;
	}

	@Test
	void createExpense_namesTheAmountAndTheDescription() {
		assertThat(preview("create_expense", """
				{"amount":500,"description":"Lunch","category":"Food"}
				""").message())
				.isEqualTo("Create expense ₱500 for Lunch?");
	}

	@Test
	void createBudget_namesTheCategoryAndTheLimit() {
		assertThat(preview("create_budget", """
				{"categoryName":"Food","month":"2026-09","amountLimit":5000}
				""").message())
				.isEqualTo("Create budget for Food — ₱5000?");
	}

	/** Absent fields read as placeholders rather than as "null" in the user's face. */
	@Test
	void createBudget_withNothingNamed_fallsBackToPlaceholders() {
		assertThat(preview("create_budget", "{}").message())
				.isEqualTo("Create budget for category — ₱0?");
	}

	@Test
	void createGoal_namesTheGoalAndTheTarget() {
		assertThat(preview("create_goal", """
				{"name":"Emergency fund","targetAmount":50000}
				""").message())
				.isEqualTo("Create goal \"Emergency fund\" — ₱50000?");
	}

	@Test
	void createRecurring_namesTheAmountAndTheFrequency() {
		assertThat(preview("create_recurring", """
				{"name":"Netflix","amount":549,"frequency":"MONTHLY"}
				""").message())
				.isEqualTo("Create recurring \"Netflix\" — ₱549/monthly?");
	}

	@Test
	void createRecurring_withNoFrequency_saysMonthly() {
		assertThat(preview("create_recurring", """
				{"name":"Netflix","amount":549}
				""").message())
				.isEqualTo("Create recurring \"Netflix\" — ₱549/monthly?");
	}

	@Test
	void createCategory_namesTheCategory() {
		assertThat(preview("create_category", """
				{"name":"Groceries"}
				""").message())
				.isEqualTo("Create category \"Groceries\"?");
	}

	@Test
	void deleteExpenses_byIds_countsThemAndWarnsThatItIsFinal() {
		assertThat(preview("delete_expenses", """
				{"ids":[1,2,3]}
				""").message())
				.isEqualTo("Delete 3 expense(s) by ID? This cannot be undone.");
	}

	@Test
	void deleteExpenses_byFilters_spellsOutTheFiltersItWillApply() {
		assertThat(preview("delete_expenses", """
				{"category":"Food","from":"2026-09-01","to":"2026-09-10"}
				""").message())
				.isEqualTo("Delete all expenses matching [category=Food from=2026-09-01 to=2026-09-10]? This cannot be undone.");
	}

	/** An empty id list is not a set of ids — the message falls through to the filter shape. */
	@Test
	void deleteExpenses_withAnEmptyIdList_describesTheFiltersInstead() {
		assertThat(preview("delete_expenses", """
				{"ids":[],"category":"Food"}
				""").message())
				.isEqualTo("Delete all expenses matching [category=Food]? This cannot be undone.");
	}

	@Test
	void deleteExpenses_byDateRangeAlone_namesOnlyTheDates() {
		assertThat(preview("delete_expenses", """
				{"from":"2026-09-01","to":"2026-09-10"}
				""").message())
				.isEqualTo("Delete all expenses matching [from=2026-09-01 to=2026-09-10]? This cannot be undone.");
	}

	@Test
	void recategorizeExpenses_namesBothCategories() {
		assertThat(preview("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining"}
				""").message())
				.isEqualTo("Move all expenses from \"Food\" to \"Dining\"? This cannot be undone.");
	}

	@Test
	void recategorizeExpenses_withNeitherCategoryNamed_fallsBackToQuestionMarks() {
		assertThat(preview("recategorize_expenses", "{}").message())
				.isEqualTo("Move all expenses from \"?\" to \"?\"? This cannot be undone.");
	}

	/** The preview payload is the tool call itself, so confirm can replay it unchanged. */
	@Test
	@SuppressWarnings("unchecked")
	void thePreviewPayload_carriesTheToolNameAndTheParamsBack() {
		ChatResponse resp = preview("create_expense", """
				{"amount":500,"description":"Lunch","category":"Food"}
				""");

		Map<String, Object> data = (Map<String, Object>) resp.result();
		assertThat(data.get("toolName")).isEqualTo("create_expense");
		assertThat((Map<String, Object>) data.get("params"))
				.containsEntry("description", "Lunch")
				.containsEntry("category", "Food");
	}
}
