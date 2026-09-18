package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.ExpenseType;
import com.teng.app.gastosai.entity.Project;
import com.teng.app.gastosai.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The branching of the chat assistant's write paths (TEN-401): create, update and delete expense,
 * and the recategorize path. Unit tests over the service with every collaborator mocked — the
 * branches here decide whether a user's row is written or destroyed, and none of them needs a
 * database to be decided.
 */
@ExtendWith(MockitoExtension.class)
class ChatActionServiceWritePathsTest extends ChatActionServiceUnitTestSupport {

	private static Expense expense(long id, String description, String amount, LocalDateTime date) {
		return Expense.builder()
				.id(id)
				.description(description)
				.amount(new BigDecimal(amount))
				.date(date)
				.build();
	}

	private static Expense storedExpense() {
		return Expense.builder()
				.id(7L)
				.description("Dinner")
				.amount(new BigDecimal("900"))
				.date(LocalDateTime.of(2026, 8, 1, 0, 0))
				.category(Category.builder().id(3L).name("Food").build())
				.project(Project.builder().id(4L).name("Client A").build())
				.expenseType(ExpenseType.BUSINESS)
				.reimbursable(true)
				.currency("USD")
				.exchangeRate(new BigDecimal("56.500000"))
				.categoryOverridden(true)
				.build();
	}

	// --- create_expense: the duplicate gate ---

	@Test
	void createExpense_aMatchingRecentExpense_isOfferedAsADuplicateRatherThanWritten() {
		when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any()))
				.thenReturn(List.of(expense(99L, " Lunch ", "500.00", LocalDateTime.now().minusDays(1))));

		// The comparison ignores case and surrounding whitespace on both sides.
		ChatResponse resp = run("create_expense", """
				{"amount":500,"description":"lunch","category":"Food"}
				""");

		assertThat(resp.type()).isEqualTo("disambiguate");
		assertThat(resp.message()).contains("add anyway?");
		assertThat(resp.message()).contains("₱500.00");
		verify(expenseService, never()).create(any(), any(), any());
	}

	/** The disambiguate payload is what the client hands back to {@code /ai/chat/confirm}. */
	@Test
	@SuppressWarnings("unchecked")
	void createExpense_theDuplicateOffer_carriesTheToolCallBackForConfirmation() {
		when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any()))
				.thenReturn(List.of(expense(99L, "Lunch", "500", LocalDateTime.now().minusDays(1))));

		ChatResponse resp = run("create_expense", """
				{"amount":500,"description":"Lunch"}
				""");

		Map<String, Object> data = (Map<String, Object>) resp.result();
		assertThat(data.get("toolName")).isEqualTo("create_expense");
		assertThat(data.get("existingId")).isEqualTo(99L);
		assertThat((Map<String, Object>) data.get("params")).containsEntry("description", "Lunch");
	}

	/** A duplicate with no date is described as "recently" rather than crashing on the null. */
	@Test
	void createExpense_aDuplicateWithNoDate_isDescribedAsRecent() {
		when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any()))
				.thenReturn(List.of(expense(99L, "Lunch", "500", null)));

		ChatResponse resp = run("create_expense", """
				{"amount":500,"description":"Lunch"}
				""");

		assertThat(resp.type()).isEqualTo("disambiguate");
		assertThat(resp.message()).contains("on recently");
	}

	@Test
	void createExpense_noMatchingRecentExpense_writesTheExpenseAsQuickAdd() {
		when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of());

		ChatResponse resp = run("create_expense", """
				{"amount":500,"description":"Lunch","category":"Food","date":"2026-09-01"}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Expense created: ₱500 — Lunch");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).create(req.capture(), any(), eq(ExpenseSource.QUICK_ADD));
		assertThat(req.getValue().amount()).isEqualByComparingTo("500");
		assertThat(req.getValue().category()).isEqualTo("Food");
		assertThat(req.getValue().date()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
	}

	@Test
	void createExpense_anAbsentCategory_fallsBackToUncategorizedAndAnAbsentDateStaysNull() {
		when(expenseRepository.findByUserAndDateAfterOrderByDateDesc(any(), any())).thenReturn(List.of());

		run("create_expense", """
				{"amount":120.50,"description":"Jeep fare"}
				""");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).create(req.capture(), any(), eq(ExpenseSource.QUICK_ADD));
		assertThat(req.getValue().category()).isEqualTo("Uncategorized");
		assertThat(req.getValue().date()).isNull();
	}

	// --- update_expense ---

	/**
	 * An edit that names no category re-states the row's own category, currency, rate, type,
	 * reimbursable flag and project rather than letting PUT semantics blank them.
	 */
	@Test
	void updateExpense_anEditThatNamesNoCategory_restatesTheRowRatherThanBlankingIt() {
		Expense existing = storedExpense();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(existing));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));

		ChatResponse resp = run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner with client"}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Expense #7 updated.");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).update(eq(7L), req.capture(), any());
		assertThat(req.getValue().category()).isEqualTo("Food");
		assertThat(req.getValue().currency()).isEqualTo("USD");
		assertThat(req.getValue().exchangeRate()).isEqualByComparingTo("56.500000");
		assertThat(req.getValue().expenseType()).isEqualTo("BUSINESS");
		assertThat(req.getValue().reimbursable()).isTrue();
		assertThat(req.getValue().project()).isEqualTo("Client A");
		assertThat(req.getValue().description()).isEqualTo("Dinner with client");
	}

	/**
	 * {@code update} re-runs categorise over the re-stated category and may clear the flag, so the
	 * flag the row had is put back.
	 */
	@Test
	void updateExpense_anEditThatNamesNoCategory_putsBackTheOverrideFlagUpdateMayHaveCleared() {
		Expense afterUpdate = storedExpense();
		afterUpdate.setCategoryOverridden(false);
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(storedExpense()));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(afterUpdate));

		run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner with client"}
				""");

		ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
		verify(expenseRepository).save(saved.capture());
		assertThat(saved.getValue().isCategoryOverridden()).isTrue();
	}

	/** The flag is only written when {@code update} actually moved it. */
	@Test
	void updateExpense_anUnmovedOverrideFlag_isNotRewritten() {
		Expense existing = storedExpense();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(existing));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));

		run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner with client"}
				""");

		verify(expenseRepository, never()).save(any());
	}

	@Test
	void updateExpense_anEditThatNamesACategory_appliesItAndLeavesTheOverrideFlagToTheService() {
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(storedExpense()));

		run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner","category":"Dining"}
				""");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).update(eq(7L), req.capture(), any());
		assertThat(req.getValue().category()).isEqualTo("Dining");
		verify(expenseRepository, never()).findById(any());
		verify(expenseRepository, never()).save(any());
	}

	/** A blank category is the model saying nothing, not the user asking for a blank one. */
	@Test
	void updateExpense_aBlankCategory_countsAsUnstatedAndRestatesTheRowsOwn() {
		Expense existing = storedExpense();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(existing));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));

		run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner","category":"   "}
				""");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).update(eq(7L), req.capture(), any());
		assertThat(req.getValue().category()).isEqualTo("Food");
	}

	@Test
	void updateExpense_aRowWithNoCategoryAndNoType_restatesNullsRatherThanInventingValues() {
		Expense bare = Expense.builder()
				.id(7L)
				.description("Dinner")
				.amount(new BigDecimal("900"))
				.category(null)
				.expenseType(null)
				.project(null)
				.build();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(bare));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(bare));

		run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner","date":"2026-09-02"}
				""");

		ArgumentCaptor<ExpenseRequest> req = ArgumentCaptor.forClass(ExpenseRequest.class);
		verify(expenseService).update(eq(7L), req.capture(), any());
		assertThat(req.getValue().category()).isNull();
		assertThat(req.getValue().expenseType()).isNull();
		assertThat(req.getValue().project()).isNull();
		assertThat(req.getValue().date()).isEqualTo(LocalDateTime.of(2026, 9, 2, 0, 0));
	}

	/** An ADMIN reads the row unscoped — the same lookup rule {@code update} itself applies. */
	@Test
	void updateExpense_anAdmin_readsTheRowWithoutTheOwnerScope() {
		User admin = admin();
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(storedExpense()));

		ChatResponse resp = run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner"}
				""", "execute", admin);

		assertThat(resp.type()).isEqualTo("action");
		verify(expenseRepository, never()).findByIdAndUser(any(), any());
		verify(expenseService).update(eq(7L), any(), eq(admin));
	}

	@Test
	void updateExpense_aRowTheCallerCannotSee_isReportedAsNotFound() {
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.empty());

		ChatResponse resp = run("update_expense", """
				{"id":7,"amount":950,"description":"Dinner"}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).isEqualTo("I couldn't find that item.");
		verify(expenseService, never()).update(any(), any(), any());
	}

	// --- delete_expense: four ways to name a row, and the refusal ---

	@Test
	void deleteExpense_anExplicitId_isDeletedDirectly() {
		ChatResponse resp = run("delete_expense", """
				{"id":42}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Expense #42 has been deleted.");
		verify(expenseService).delete(eq(42L), any());
	}

	@Test
	void deleteExpense_latest_deletesTheMostRecentExpenseAndNamesIt() {
		when(expenseRepository.findTopByUserOrderByDateDesc(any()))
				.thenReturn(Optional.of(expense(11L, "Grab ride", "250.5", LocalDateTime.now())));

		ChatResponse resp = run("delete_expense", """
				{"latest":true}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Deleted \"Grab ride\" (₱250.50) from your expenses.");
		verify(expenseService).delete(eq(11L), any());
	}

	@Test
	void deleteExpense_latest_withNothingToDelete_isReportedAsNotFound() {
		when(expenseRepository.findTopByUserOrderByDateDesc(any())).thenReturn(Optional.empty());

		ChatResponse resp = run("delete_expense", """
				{"latest":true}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).isEqualTo("I couldn't find that item.");
		verify(expenseService, never()).delete(any(), any());
	}

	@Test
	void deleteExpense_aDescriptionMatchingNothing_deletesNothingAndSaysSo() {
		when(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(any(), eq("sushi")))
				.thenReturn(List.of());

		ChatResponse resp = run("delete_expense", """
				{"description":"sushi"}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("No expense found matching \"sushi\"");
		verify(expenseService, never()).delete(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void deleteExpense_aDescriptionMatchingSeveral_asksWhichOneAndDeletesNone() {
		when(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(any(), eq("coffee")))
				.thenReturn(List.of(
						expense(1L, "Coffee", "150", LocalDateTime.of(2026, 9, 1, 8, 0)),
						expense(2L, "Coffee beans", "480", null)));

		ChatResponse resp = run("delete_expense", """
				{"description":"coffee"}
				""");

		assertThat(resp.type()).isEqualTo("disambiguate");
		assertThat(resp.message()).contains("Found 2 expenses matching \"coffee\"");
		List<Map<String, Object>> items = (List<Map<String, Object>>) resp.result();
		assertThat(items).hasSize(2);
		assertThat(items.get(0)).containsEntry("id", 1L).containsEntry("date", "2026-09-01");
		// A row with no date renders as an empty string rather than "null".
		assertThat(items.get(1)).containsEntry("date", "");
		verify(expenseService, never()).delete(any(), any());
	}

	@Test
	void deleteExpense_aDescriptionMatchingExactlyOne_deletesIt() {
		when(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(any(), eq("coffee")))
				.thenReturn(List.of(expense(1L, "Coffee", "150", LocalDateTime.of(2026, 9, 1, 8, 0))));

		ChatResponse resp = run("delete_expense", """
				{"description":"coffee"}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Deleted \"Coffee\" (₱150.00) from your expenses.");
		verify(expenseService).delete(eq(1L), any());
	}

	@Test
	void deleteExpense_noIdNoLatestNoDescription_asksWhichExpenseAndDeletesNothing() {
		ChatResponse resp = run("delete_expense", """
				{"description":"  "}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Which expense would you like to delete?");
		verify(expenseService, never()).delete(any(), any());
	}

	/** A zero id is not an id — it falls through to the other resolution paths. */
	@Test
	void deleteExpense_aZeroId_isNotTreatedAsAnId() {
		ChatResponse resp = run("delete_expense", """
				{"id":0}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Which expense would you like to delete?");
		verify(expenseService, never()).delete(any(), any());
	}

	// --- delete_expenses: the bulk path ---

	/** Never stage a "delete everything": no ids and no filters is refused, not executed. */
	@Test
	void deleteExpenses_withNoIdsAndNoFilters_refusesAndDeletesNothing() {
		ChatResponse resp = run("delete_expenses", "{}");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).isEqualTo(
				"Tell me which expenses to delete — by date range, category, or specific items.");
		verify(expenseService, never()).delete(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void deleteExpenses_byDateRange_deletesEveryRowItRead() {
		when(expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
				any(),
				eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
				eq(LocalDateTime.of(2026, 9, 11, 0, 0))))
				.thenReturn(List.of(expense(1L, "Lunch", "300", null), expense(2L, "Grab", "200", null)));

		ChatResponse resp = run("delete_expenses", """
				{"from":"2026-09-01","to":"2026-09-10"}
				""");

		assertThat(resp.message()).isEqualTo("Deleted 2 expense(s).");
		assertThat((Map<String, Object>) resp.result()).containsEntry("deleted", 2);
		verify(expenseService).delete(eq(1L), any());
		verify(expenseService).delete(eq(2L), any());
	}

	@Test
	void deleteExpenses_withOnlyAFromDate_deletesFromThatDayOnward() {
		when(expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(
				any(), eq(LocalDateTime.of(2026, 9, 1, 0, 0))))
				.thenReturn(List.of(expense(1L, "Lunch", "300", null)));

		ChatResponse resp = run("delete_expenses", """
				{"from":"2026-09-01"}
				""");

		assertThat(resp.message()).isEqualTo("Deleted 1 expense(s).");
		verify(expenseService).delete(eq(1L), any());
	}

	@Test
	void deleteExpenses_withOnlyAToDate_deletesUpToTheEndOfThatDay() {
		when(expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(
				any(), eq(LocalDateTime.of(2026, 9, 11, 0, 0))))
				.thenReturn(List.of(expense(1L, "Lunch", "300", null)));

		ChatResponse resp = run("delete_expenses", """
				{"to":"2026-09-10"}
				""");

		assertThat(resp.message()).isEqualTo("Deleted 1 expense(s).");
		verify(expenseService).delete(eq(1L), any());
	}

	@Test
	void deleteExpenses_withACategoryFilter_deletesOnlyThatCategory() {
		when(expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(
				any(), eq(LocalDateTime.of(2026, 9, 1, 0, 0))))
				.thenReturn(List.of(food(), transport()));

		ChatResponse resp = run("delete_expenses", """
				{"from":"2026-09-01","category":"food"}
				""");

		assertThat(resp.message()).isEqualTo("Deleted 1 expense(s).");
		verify(expenseService).delete(eq(1L), any());
		verify(expenseService, never()).delete(eq(2L), any());
	}

	/** An id repeated in the tool call is one row, so it is deleted once. */
	@Test
	void deleteExpenses_aRepeatedId_isDeletedOnce() {
		when(expenseRepository.existsByIdAndUser(eq(1L), any())).thenReturn(true);

		ChatResponse resp = run("delete_expenses", """
				{"ids":[1,1]}
				""");

		assertThat(resp.message()).isEqualTo("Deleted 1 expense(s).");
		verify(expenseService).delete(eq(1L), any());
	}

	/** An ADMIN resolves named ids unscoped — the rule {@code ExpenseService.delete} applies. */
	@Test
	void deleteExpenses_anAdmin_resolvesNamedIdsWithoutTheOwnerScope() {
		User admin = admin();
		when(expenseRepository.existsById(1L)).thenReturn(true);

		ChatResponse resp = run("delete_expenses", """
				{"ids":[1]}
				""", "execute", admin);

		assertThat(resp.message()).isEqualTo("Deleted 1 expense(s).");
		verify(expenseRepository, never()).existsByIdAndUser(any(), any());
		verify(expenseService).delete(1L, admin);
	}

	// --- recategorize_expenses ---

	private Expense food() {
		return Expense.builder().id(1L).description("Lunch").amount(new BigDecimal("300"))
				.category(Category.builder().id(3L).name("Food").build()).build();
	}

	private Expense transport() {
		return Expense.builder().id(2L).description("Grab").amount(new BigDecimal("200"))
				.category(Category.builder().id(4L).name("Transport").build()).build();
	}

	private void targetCategoryIs(String name) {
		when(categoryService.getOrCreateByName(eq(name), any()))
				.thenReturn(Category.builder().id(9L).name(name).build());
	}

	@Test
	@SuppressWarnings("unchecked")
	void recategorize_withNoDates_movesOnlyTheExpensesInTheNamedCategory() {
		Expense untouched = transport();
		Expense uncategorized = Expense.builder().id(3L).description("Unknown")
				.amount(new BigDecimal("100")).category(null).build();
		when(expenseRepository.findAllByUserOrderByDateDesc(any()))
				.thenReturn(List.of(food(), untouched, uncategorized));
		targetCategoryIs("Dining");

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"food","toCategory":"Dining"}
				""");

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Moved 1 expense(s) from \"food\" to \"Dining\".");
		assertThat((Map<String, Object>) resp.result()).containsEntry("updated", 1);

		ArgumentCaptor<List<Expense>> saved = ArgumentCaptor.forClass(List.class);
		verify(expenseRepository).saveAll(saved.capture());
		assertThat(saved.getValue()).singleElement()
				.extracting(e -> e.getCategory().getName()).isEqualTo("Dining");
		assertThat(untouched.getCategory().getName()).isEqualTo("Transport");
	}

	@Test
	void recategorize_withBothDates_readsTheHalfOpenRange() {
		when(expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
				any(),
				eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
				eq(LocalDateTime.of(2026, 9, 11, 0, 0))))
				.thenReturn(List.of(food()));
		targetCategoryIs("Dining");

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining","from":"2026-09-01","to":"2026-09-10"}
				""");

		assertThat(resp.message()).contains("Moved 1 expense(s)");
	}

	@Test
	void recategorize_withOnlyAFromDate_readsFromThatDayOnward() {
		when(expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(
				any(), eq(LocalDateTime.of(2026, 9, 1, 0, 0))))
				.thenReturn(List.of(food(), transport()));
		targetCategoryIs("Dining");

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining","from":"2026-09-01"}
				""");

		assertThat(resp.message()).contains("Moved 1 expense(s)");
	}

	@Test
	void recategorize_withOnlyAToDate_readsUpToTheEndOfThatDay() {
		when(expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(
				any(), eq(LocalDateTime.of(2026, 9, 11, 0, 0))))
				.thenReturn(List.of(food()));
		targetCategoryIs("Dining");

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining","to":"2026-09-10"}
				""");

		assertThat(resp.message()).contains("Moved 1 expense(s)");
	}

	@Test
	void recategorize_withNothingMatching_movesNothingAndStillReportsZero() {
		when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(transport()));
		targetCategoryIs("Dining");

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining"}
				""");

		assertThat(resp.message()).isEqualTo("Moved 0 expense(s) from \"Food\" to \"Dining\".");
		verify(expenseRepository).saveAll(List.of());
	}

	/** A failing write is reported as a failed turn, not as a count of rows that did not move. */
	@Test
	void recategorize_aFailingWrite_isReportedAsAFailedTurn() {
		when(expenseRepository.findAllByUserOrderByDateDesc(any())).thenReturn(List.of(food()));
		targetCategoryIs("Dining");
		doThrow(new RuntimeException("constraint violation")).when(expenseRepository).saveAll(any());

		ChatResponse resp = run("recategorize_expenses", """
				{"fromCategory":"Food","toCategory":"Dining"}
				""");

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Something went wrong");
		assertThat(resp.message()).doesNotContain("constraint violation");
	}
}
