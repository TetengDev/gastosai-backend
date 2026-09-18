package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The confirm path and the conversation wrapper around both entry points (TEN-401).
 *
 * <p>Confirm is the turn that commits what a preview proposed, so what it does with params it
 * cannot use, and what it leaves behind when the write fails, are the two branches worth pinning:
 * a rejected tool call must write nothing, and a failing one must roll its transaction back rather
 * than commit half an edit.
 */
@ExtendWith(MockitoExtension.class)
class ChatActionServiceConfirmPathTest extends ChatActionServiceUnitTestSupport {

	private static Conversation conversation(long id) {
		return Conversation.builder().id(id).build();
	}

	private static ExpenseResponse expenseResponse(long id) {
		return new ExpenseResponse(id, new BigDecimal("950.00"), "Food",
				LocalDateTime.of(2026, 9, 1, 0, 0), "Dinner", "PERSONAL", false, "PHP",
				BigDecimal.ONE, new BigDecimal("950.00"), ExpenseSource.QUICK_ADD);
	}

	private Map<String, Object> params(Object... keysAndValues) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	// --- params the tool cannot use ---

	/**
	 * A create_expense call with no amount cannot be executed. The turn is refused as text, nothing
	 * is written, and the failure is audited — the client cannot make it succeed by tapping again.
	 */
	@Test
	void confirm_paramsTheToolCannotUse_writeNothingAndAreAuditedAsFailed() {
		ChatResponse resp = chatActionService.confirm(
				"create_expense", params("description", "Dinner"), null, user(), 5L);

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Something went wrong");
		verify(expenseService, never()).create(any(), any(), any());
		verify(chatAuditService).record(eq(1L), eq(5L), eq("create_expense"),
				eq(AiUsageStatus.FAILED), eq("NullPointerException"));
	}

	@Test
	void confirm_anUnknownToolName_isRejectedBeforeAnythingRuns() {
		User user = user();

		assertThatThrownBy(() -> chatActionService.confirm("drop_database", params(), null, user, null))
				.hasMessageContaining("Unknown toolName");

		verifyNoInteractions(expenseService, chatAuditService, conversationService);
	}

	/** Null params are an empty object, not an NPE — the tool then refuses them on its own terms. */
	@Test
	void confirm_nullParams_areTreatedAsAnEmptyToolCall() {
		ChatResponse resp = chatActionService.confirm("delete_expense", null, null, user(), null);

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Which expense would you like to delete?");
		verify(expenseService, never()).delete(any(), any());
	}

	// --- the turn that commits ---

	@Test
	void confirm_aTurnThatCommits_runsTheWriteAndAuditsItAsConfirmed() {
		ChatResponse resp = chatActionService.confirm(
				"delete_expense", params("id", 42), null, user(), 5L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.message()).isEqualTo("Expense #42 has been deleted.");
		verify(expenseService).delete(eq(42L), any());
		verify(chatAuditService).record(eq(1L), eq(5L), eq("delete_expense"),
				eq(AiUsageStatus.SUCCESS), eq("confirm"));
		// No English was sent, so no model ran and nothing was metered.
		verifyNoInteractions(sqlGenerator, aiUsageService);
	}

	@Test
	void confirm_aRowThatDoesNotResolve_isReportedAsNotFoundAndAudited() {
		doThrow(new com.teng.app.gastosai.exception.ResourceNotFoundException("Expense not found: 42"))
				.when(expenseService).delete(eq(42L), any());

		ChatResponse resp = chatActionService.confirm(
				"delete_expense", params("id", 42), null, user(), 5L);

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).isEqualTo("I couldn't find that item.");
		verify(chatAuditService).record(eq(1L), eq(5L), eq("delete_expense"),
				eq(AiUsageStatus.FAILED), eq("ResourceNotFoundException"));
	}

	// --- inOneTransaction ---

	/** The update handler's read and write commit once, together. */
	@Test
	void confirm_anUpdateThatSucceeds_commitsItsOneTransaction() {
		TransactionStatus status = new SimpleTransactionStatus();
		when(transactionManager.getTransaction(any())).thenReturn(status);
		Expense existing = Expense.builder().id(7L).description("Dinner")
				.amount(new BigDecimal("900"))
				.category(Category.builder().id(3L).name("Food").build()).build();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(existing));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));
		when(expenseService.update(eq(7L), any(), any())).thenReturn(expenseResponse(7L));

		ChatResponse resp = chatActionService.confirm(
				"update_expense", params("id", 7, "amount", 950, "description", "Dinner"),
				null, user(), null);

		assertThat(resp.type()).isEqualTo("action");
		verify(transactionManager).commit(status);
		verify(transactionManager, never()).rollback(any());
	}

	/** A failing write rolls the whole handler back rather than committing what ran before it. */
	@Test
	void confirm_anUpdateThatFails_rollsBackRatherThanCommitting() {
		TransactionStatus status = new SimpleTransactionStatus();
		when(transactionManager.getTransaction(any())).thenReturn(status);
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(
				Expense.builder().id(7L).description("Dinner").amount(new BigDecimal("900")).build()));
		when(expenseService.update(eq(7L), any(), any()))
				.thenThrow(new RuntimeException("optimistic lock"));

		ChatResponse resp = chatActionService.confirm(
				"update_expense", params("id", 7, "amount", 950, "description", "Dinner"),
				null, user(), null);

		assertThat(resp.type()).isEqualTo("text");
		assertThat(resp.message()).contains("Something went wrong");
		verify(transactionManager).rollback(status);
		verify(transactionManager, never()).commit(any());
		// The flag restore that follows the update never ran.
		verify(expenseRepository, never()).save(any());
	}

	// --- the conversation wrapper ---

	@Test
	void confirm_tagsTheResponseWithTheConversationAndRecordsTheTurn() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);

		ChatResponse resp = chatActionService.confirm(
				"delete_expense", params("id", 42), null, user(), 12L);

		assertThat(resp.conversationId()).isEqualTo(12L);
		verify(conversationService).recordTurn(eq(conversation), eq("[confirmed] delete_expense"), any());
	}

	@Test
	void confirm_aFailingContextLoad_doesNotStopTheAction() {
		when(conversationService.getOrCreate(any(), eq(12L)))
				.thenThrow(new RuntimeException("conversation table unavailable"));

		ChatResponse resp = chatActionService.confirm(
				"delete_expense", params("id", 42), null, user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.conversationId()).isNull();
		verify(expenseService).delete(eq(42L), any());
		// The conversation id from the request is still what the audit row is filed under.
		verify(chatAuditService).record(eq(1L), eq(12L), eq("delete_expense"),
				eq(AiUsageStatus.SUCCESS), eq("confirm"));
	}

	@Test
	void confirm_aFailingHistoryWrite_stillReturnsTheAction() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		doThrow(new RuntimeException("history table unavailable"))
				.when(conversationService).recordTurn(any(), any(), any());

		ChatResponse resp = chatActionService.confirm(
				"delete_expense", params("id", 42), null, user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.conversationId()).isNull();
	}

	/** The expense a turn wrote becomes the referent the next turn's "it" resolves to. */
	@Test
	void confirm_anExpenseWrite_isRememberedAsTheConversationsLastEntity() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		Expense existing = Expense.builder().id(7L).description("Dinner")
				.amount(new BigDecimal("900"))
				.category(Category.builder().id(3L).name("Food").build()).build();
		when(expenseRepository.findByIdAndUser(eq(7L), any())).thenReturn(Optional.of(existing));
		when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));
		when(expenseService.update(eq(7L), any(), any())).thenReturn(expenseResponse(7L));

		chatActionService.confirm("update_expense",
				params("id", 7, "amount", 950, "description", "Dinner"), null, user(), 12L);

		verify(conversationService).recordEntity(conversation, "expense", 7L);
	}

	// --- the conversation wrapper on the natural-language path ---

	@Test
	void dispatch_withAConversation_tagsTheResponseAndRecordsTheRedactedTurn() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(conversationService.recentTranscript(eq(conversation), eq(6))).thenReturn("");
		when(sqlGenerator.classifyIntent(any()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expense", "{\"id\":42}")));

		ChatResponse resp = chatActionService.dispatch("delete expense 42", "execute", user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.conversationId()).isEqualTo(12L);
		// The turn is stored redacted — recordTurn is handed redact(message), not the raw message.
		verify(conversationService).recordTurn(eq(conversation), eq("delete expense 42"), any());
	}

	@Test
	void dispatch_aFailingContextLoad_stillAnswersTheTurn() {
		when(conversationService.getOrCreate(any(), eq(12L)))
				.thenThrow(new RuntimeException("conversation table unavailable"));
		when(sqlGenerator.classifyIntent(any()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expense", "{\"id\":42}")));

		ChatResponse resp = chatActionService.dispatch("delete expense 42", "execute", user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.conversationId()).isNull();
		verify(chatAuditService).record(eq(1L), isNull(), eq("delete_expense"),
				eq(AiUsageStatus.SUCCESS), isNull());
	}

	@Test
	void dispatch_aFailingHistoryWrite_stillAnswersTheTurn() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(conversationService.recentTranscript(eq(conversation), eq(6))).thenReturn("");
		doThrow(new RuntimeException("history table unavailable"))
				.when(conversationService).recordTurn(any(), any(), any());
		when(sqlGenerator.classifyIntent(any()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("delete_expense", "{\"id\":42}")));

		ChatResponse resp = chatActionService.dispatch("delete expense 42", "execute", user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		assertThat(resp.conversationId()).isNull();
	}

	/**
	 * A recurring turn overwrites the referent too, so an ambiguous "delete it" afterwards resolves
	 * to the recurring expense and not to an older expense row (TEN-364).
	 */
	@Test
	void dispatch_aRecurringWrite_isRememberedAsTheConversationsLastEntity() {
		Conversation conversation = conversation(12L);
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(conversationService.recentTranscript(eq(conversation), eq(6))).thenReturn("");
		when(recurringExpenseRepository.findByIdAndUser(eq(5L), any())).thenReturn(Optional.of(
				RecurringExpense.builder().id(5L).name("Netflix").amount(new BigDecimal("549"))
						.frequency(Frequency.MONTHLY).dayOfMonth(1).build()));
		when(recurringExpenseService.updateWithBase(eq(5L), any(), any())).thenReturn(
				new RecurringExpenseWithBase(
						new RecurringExpenseResponse(5L, "Netflix", new BigDecimal("649.00"), "Bills",
								Frequency.MONTHLY, 1, null, null, true, "PHP", BigDecimal.ONE),
						new BigDecimal("649.00")));
		when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(
				new ChatToolCall("update_recurring", "{\"id\":5,\"amount\":649}")));

		ChatResponse resp = chatActionService.dispatch("make it 649", "execute", user(), 12L);

		assertThat(resp.type()).isEqualTo("action");
		verify(conversationService).recordEntity(conversation, "recurring", 5L);
	}
}
