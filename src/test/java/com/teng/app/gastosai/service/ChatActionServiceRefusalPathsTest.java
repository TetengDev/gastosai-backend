package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.exception.FeatureLockedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two branches that decide whether a chat turn is allowed to run at all (TEN-403).
 *
 * <p>TEN-401 covered what a turn <em>does</em>; these cover the refusals that come before and
 * around it, both of which are a rethrow rather than a returned message and are therefore the kind
 * of behaviour a later refactor can quietly swallow:
 *
 * <ul>
 *   <li>the AI quota gate, which must refuse an over-cap user before any model call is made — no
 *       tokens are spent on a turn that is already denied, so nothing is metered or audited;</li>
 *   <li>the plan category cap ({@link FeatureLockedException}, TEN-319/TEN-327), which is caught
 *       ahead of the generic handler only to meter and audit the failed turn, then rethrown so
 *       {@code GlobalExceptionHandler} answers 402 naming {@code CUSTOM_CATEGORIES} instead of a
 *       200 "please rephrase" the user cannot act on.</li>
 * </ul>
 *
 * <p>Collaborators are mocked (see {@link ChatActionServiceUnitTestSupport}) — no Spring context and
 * no database. Whether a given user is actually over the cap is {@code AiQuotaService}'s decision
 * and is tested in {@code AiQuotaServiceTest}; what is asserted here is that this service consults
 * that gate first and lets its refusal through untouched.
 */
@ExtendWith(MockitoExtension.class)
class ChatActionServiceRefusalPathsTest extends ChatActionServiceUnitTestSupport {

	private static Map<String, Object> params(Object... keysAndValues) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	private static FeatureLockedException categoryCap() {
		return new FeatureLockedException(FeatureKey.CUSTOM_CATEGORIES,
				"Your plan is limited to 5 categories. Upgrade to add more.");
	}

	// --- the quota gate ---

	/**
	 * The gate runs before the classifier, so an over-cap user never reaches a paid model call.
	 * Nothing is metered or audited either: there is no turn to meter, only a refused request.
	 */
	@Test
	void dispatch_aUserOverTheAiQuota_isRefusedBeforeTheClassifierRuns() {
		User user = user();
		doThrow(new AiQuotaExceededException())
				.when(aiQuotaService).assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT);

		assertThatThrownBy(() -> chatActionService.dispatch("add 500 lunch", "execute", user))
				.isInstanceOf(AiQuotaExceededException.class)
				.hasMessageContaining("monthly AI limit");

		verifyNoInteractions(sqlGenerator, aiUsageService, chatAuditService, expenseService);
	}

	/** Same gate on the conversation-aware overload: the turn is refused, not stored. */
	@Test
	void dispatchWithAConversation_aUserOverTheAiQuota_isRefusedBeforeTheClassifierRuns() {
		User user = user();
		Conversation conversation = Conversation.builder().id(12L).build();
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(conversationService.recentTranscript(eq(conversation), eq(6))).thenReturn("");
		doThrow(new AiQuotaExceededException())
				.when(aiQuotaService).assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT);

		assertThatThrownBy(() -> chatActionService.dispatch("add 500 lunch", "execute", user, 12L))
				.isInstanceOf(AiQuotaExceededException.class);

		verifyNoInteractions(sqlGenerator, aiUsageService, chatAuditService);
		// A refused turn is not history: nothing is written under the conversation.
		verify(conversationService, never()).recordTurn(any(), any(), any());
		verify(conversationService, never()).recordEntity(any(), any(), any());
	}

	/**
	 * An ADMIN is not refused. The bypass itself lives in {@code AiQuotaService#assertWithinQuota}
	 * (the {@code entitlements.admin()} early return); what this pins is that the chat path routes
	 * the admin through the same gate with the same feature and then runs the turn normally, rather
	 * than short-circuiting the gate on its own.
	 */
	@Test
	void dispatch_anAdmin_passesTheQuotaGateAndRunsTheTurn() {
		User admin = admin();
		doNothing().when(aiQuotaService).assertWithinQuota(admin, AiFeature.CHAT_CRUD_ASSISTANT);
		when(sqlGenerator.classifyIntent(any()))
				.thenReturn(LlmResult.ofValue(new ChatToolCall("text", "How can I help?")));

		ChatResponse resp = chatActionService.dispatch("hello", "execute", admin);

		assertThat(resp.type()).isEqualTo("text");
		verify(aiQuotaService).assertWithinQuota(admin, AiFeature.CHAT_CRUD_ASSISTANT);
		verify(chatAuditService).record(eq(2L), isNull(), eq("text"),
				eq(AiUsageStatus.SUCCESS), isNull());
	}

	// --- the plan category cap ---

	/**
	 * A category the plan has no room for is a 402, not a 200: the exception leaves
	 * {@code dispatchCore} untouched rather than being turned into the generic handler's
	 * "Something went wrong … please rephrase" text response — advice that cannot work, because
	 * rephrasing does not buy plan headroom. Deleting the rethrow fails this assertion. The failed
	 * turn is still metered and audited under the tool that was resolved.
	 */
	@Test
	void dispatch_aPlanCategoryCapRefusal_propagatesAndIsMeteredAndAudited() {
		when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(
				new ChatToolCall("create_category", "{\"name\":\"Crypto\"}")));
		when(categoryService.create(any(), any())).thenThrow(categoryCap());

		assertThatThrownBy(() -> chatActionService.dispatch("add a Crypto category", "execute", user()))
				.isInstanceOf(FeatureLockedException.class)
				.hasMessageContaining("limited to 5 categories")
				.extracting(e -> ((FeatureLockedException) e).getFeature())
				.isEqualTo(FeatureKey.CUSTOM_CATEGORIES);

		verify(aiUsageService).record(eq(1L), anyString(), anyString(),
				eq(AiFeature.CHAT_CRUD_ASSISTANT), isNull(), isNull(),
				eq(AiUsageStatus.FAILED), eq("FeatureLockedException"));
		verify(chatAuditService).record(eq(1L), isNull(), eq("create_category"),
				eq(AiUsageStatus.FAILED), eq("FeatureLockedException"));
	}

	/** The audit row carries the conversation the refused turn belongs to. */
	@Test
	void dispatchWithAConversation_aPlanCategoryCapRefusal_isAuditedUnderThatConversation() {
		Conversation conversation = Conversation.builder().id(12L).build();
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(conversationService.recentTranscript(eq(conversation), eq(6))).thenReturn("");
		when(sqlGenerator.classifyIntent(any())).thenReturn(LlmResult.ofValue(
				new ChatToolCall("create_category", "{\"name\":\"Crypto\"}")));
		when(categoryService.create(any(), any())).thenThrow(categoryCap());
		User user = user();

		assertThatThrownBy(() -> chatActionService.dispatch("add a Crypto category", "execute", user, 12L))
				.isInstanceOf(FeatureLockedException.class);

		verify(chatAuditService).record(eq(1L), eq(12L), eq("create_category"),
				eq(AiUsageStatus.FAILED), eq("FeatureLockedException"));
	}

	/**
	 * The same refusal on the confirm path. Tapping Confirm again cannot buy headroom either, so
	 * {@code confirmCore} rethrows rather than answering "please rephrase".
	 *
	 * <p>Nothing is metered here and that is correct, not an omission: confirm sends no English, so
	 * no model runs and there are no tokens to charge (see {@code ChatActionService#confirm}). The
	 * audit row is what records the refused turn on this path.
	 */
	@Test
	void confirm_aPlanCategoryCapRefusal_propagatesAndIsAudited() {
		when(categoryService.create(any(), any())).thenThrow(categoryCap());
		User user = user();

		assertThatThrownBy(() -> chatActionService.confirm(
				"create_category", params("name", "Crypto"), null, user, 5L))
				.isInstanceOf(FeatureLockedException.class)
				.extracting(e -> ((FeatureLockedException) e).getFeature())
				.isEqualTo(FeatureKey.CUSTOM_CATEGORIES);

		verify(chatAuditService).record(eq(1L), eq(5L), eq("create_category"),
				eq(AiUsageStatus.FAILED), eq("FeatureLockedException"));
		verifyNoInteractions(sqlGenerator, aiUsageService);
	}

	/** A refused confirm is not stored as a conversation turn either. */
	@Test
	void confirm_aPlanCategoryCapRefusal_doesNotRecordTheTurn() {
		Conversation conversation = Conversation.builder().id(12L).build();
		when(conversationService.getOrCreate(any(), eq(12L))).thenReturn(conversation);
		when(categoryService.create(any(), any())).thenThrow(categoryCap());
		User user = user();

		assertThatThrownBy(() -> chatActionService.confirm(
				"create_category", params("name", "Crypto"), null, user, 12L))
				.isInstanceOf(FeatureLockedException.class);

		verify(conversationService, never()).recordTurn(any(), any(), any());
		verify(conversationService, never()).recordEntity(any(), any(), any());
	}
}
