package com.teng.app.gastosai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.ai.ChatTool;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.LlmUsage;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.BudgetSummaryResponse;
import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatActionService {

	/** Caller opts in to executing a create action immediately instead of returning a confirmation preview. */
	private static final String MODE_EXECUTE = "execute";

	/** Like {@link #MODE_EXECUTE} but also bypasses the duplicate-expense check (user already chose "add anyway"). */
	private static final String MODE_FORCE = "force";

	private static boolean isRunMode(String mode) {
		return MODE_EXECUTE.equals(mode) || MODE_FORCE.equals(mode);
	}

	private final SqlGenerator sqlGenerator;
	private final ExpenseService expenseService;
	private final BudgetService budgetService;
	private final SavingsGoalService savingsGoalService;
	private final RecurringExpenseService recurringExpenseService;
	private final CategoryService categoryService;
	private final UserProfileService userProfileService;
	private final EntitlementService entitlementService;
	private final AlertService alertService;
	private final ExpenseRepository expenseRepository;
	private final RecurringExpenseRepository recurringExpenseRepository;
	private final BudgetRepository budgetRepository;
	private final SavingsGoalRepository savingsGoalRepository;
	private final PlatformTransactionManager transactionManager;
	private final ObjectMapper objectMapper;
	private final AiQuotaService aiQuotaService;
	private final AiUsageService aiUsageService;
	private final AiRedactionService aiRedactionService;
	private final AiManagedProperties aiManagedProperties;
	private final AiProviderProperties aiProviderProperties;
	private final OpenAiProperties openAiProperties;
	private final ClaudeProperties claudeProperties;
	private final ConversationService conversationService;
	private final ChatAuditService chatAuditService;

	/**
	 * Conversation-aware entry point: runs the stateless {@link #dispatch(String, String, User)} and then
	 * persists the turn under the given (or a newly created) conversation, returning the response tagged
	 * with its conversation id. Persistence failures never fail the chat — they are logged and swallowed.
	 */
	public ChatResponse dispatch(String message, String mode, User user, Long conversationId) {
		Conversation conversation = null;
		String effectiveMessage = message;
		try {
			conversation = conversationService.getOrCreate(user, conversationId);
			effectiveMessage = withContext(conversation, message);
		} catch (Exception e) {
			log.warn("chat_context_load_failed", e);
		}

		ChatResponse response = dispatchCore(effectiveMessage, mode, user,
				conversation != null ? conversation.getId() : null);

		if (conversation != null) {
			try {
				conversationService.recordTurn(conversation, aiRedactionService.redact(message), response);
				captureEntity(conversation, response);
				return response.withConversation(conversation.getId());
			} catch (Exception e) {
				log.warn("chat_history_persist_failed", e);
			}
		}
		return response;
	}

	/**
	 * Prefixes the message with a short transcript + last-entity hint so the intent classifier can
	 * resolve follow-ups ("delete it", "make it 500"). Hybrid: deterministic last-entity tracking +
	 * LLM context. Returns the message unchanged for a brand-new conversation.
	 */
	private String withContext(Conversation conversation, String message) {
		String transcript = conversationService.recentTranscript(conversation, 6);
		StringBuilder ctx = new StringBuilder();
		if (!transcript.isBlank()) {
			ctx.append("Conversation so far:\n").append(transcript).append("\n\n");
		}
		if ("expense".equals(conversation.getLastEntityType()) && conversation.getLastEntityId() != null) {
			ctx.append("If the user refers to \"it\" / \"that one\" / \"the last expense\" without an id, ")
					.append("use expense id ").append(conversation.getLastEntityId()).append(".\n\n");
		}
		if (ctx.length() == 0) {
			return message;
		}
		return ctx.append("Current message: ").append(message).toString();
	}

	/** Remembers the single expense a turn created/updated so the next turn can refer back to it. */
	private void captureEntity(Conversation conversation, ChatResponse response) {
		if (response.result() instanceof com.teng.app.gastosai.dto.ExpenseResponse er && er.id() != null) {
			conversationService.recordEntity(conversation, "expense", er.id());
		}
	}

	public ChatResponse dispatch(String message, String mode, User user) {
		return dispatchCore(message, mode, user, null);
	}

	private ChatResponse dispatchCore(String message, String mode, User user, Long conversationId) {
		aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT);
		int max = aiManagedProperties.getMaxPromptChars();
		String safeMessage = aiRedactionService.redact(message);
		if (safeMessage != null && safeMessage.length() > max) {
			safeMessage = safeMessage.substring(0, max);
		}
		final String finalMessage = safeMessage;
		ChatTool resolvedTool = ChatTool.TEXT;
		try {
			var intentResult = sqlGenerator.classifyIntent(finalMessage);
			LlmUsage llmUsage = intentResult.usage();
			ChatToolCall call = intentResult.value();
			ChatTool tool = ChatTool.fromKey(call.toolName());
			resolvedTool = tool;

			if (tool == ChatTool.TEXT) {
				aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
						resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
						llmUsage.inputTokens(), llmUsage.outputTokens(), AiUsageStatus.SUCCESS, null);
				chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.SUCCESS, null);
				return new ChatResponse("text", call.paramsJson(), null);
			}

			JsonNode params = objectMapper.readTree(call.paramsJson());

			if ((tool.isCreate() || tool.isDestructive()) && !isRunMode(mode)) {
				Map<String, Object> previewData = new LinkedHashMap<>();
				previewData.put("toolName", tool.key());
				previewData.put("params", objectMapper.convertValue(params, Map.class));
				aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
						resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
						llmUsage.inputTokens(), llmUsage.outputTokens(), AiUsageStatus.SUCCESS, null);
				chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.SUCCESS, "preview");
				return new ChatResponse("preview", buildPreviewMessage(tool, params), previewData);
			}

			ChatResponse response = execute(tool, params, MODE_FORCE.equals(mode), user);
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					llmUsage.inputTokens(), llmUsage.outputTokens(), AiUsageStatus.SUCCESS, null);
			chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.SUCCESS, null);
			return response;
		}
		catch (ResourceNotFoundException e) {
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					null, null, AiUsageStatus.FAILED, "ResourceNotFoundException");
			chatAuditService.record(user.getId(), conversationId, resolvedTool.key(), AiUsageStatus.FAILED, "ResourceNotFoundException");
			return new ChatResponse("text", "I couldn't find that item.", null);
		}
		catch (Exception e) {
			log.warn("chat_action_failed", e);
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					null, null, AiUsageStatus.FAILED, e.getClass().getSimpleName());
			chatAuditService.record(user.getId(), conversationId, resolvedTool.key(), AiUsageStatus.FAILED, e.getClass().getSimpleName());
			return new ChatResponse("text", "Something went wrong while handling that. Please rephrase and try again.", null);
		}
	}

	/**
	 * Structured confirm path: runs the action the server itself proposed on a {@code "preview"}
	 * turn, taking the tool and params straight back from the client.
	 *
	 * <p>No English is sent, so no classifier runs and nothing is re-parsed — tapping Confirm
	 * executes exactly what was previewed, and cannot silently resolve to a different tool or to
	 * plain text. Consequently no LLM tokens are spent and nothing is metered into {@code ai_usage};
	 * the turn is still written to the chat audit log, tagged {@code confirm}.
	 *
	 * @param toolName the preview payload's {@code toolName}; anything unknown is a 400
	 * @param mode {@code force} to bypass the duplicate-expense check ("add anyway")
	 */
	public ChatResponse confirm(String toolName, Map<String, Object> params, String mode,
			User user, Long conversationId) {
		ChatTool tool = ChatTool.fromKey(toolName);
		if (tool == ChatTool.TEXT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Unknown toolName: " + toolName);
		}

		Conversation conversation = null;
		try {
			conversation = conversationService.getOrCreate(user, conversationId);
		} catch (Exception e) {
			log.warn("chat_context_load_failed", e);
		}
		Long resolvedConversationId = conversation != null ? conversation.getId() : conversationId;

		ChatResponse response = confirmCore(tool, params, mode, user, resolvedConversationId);

		if (conversation != null) {
			try {
				conversationService.recordTurn(conversation, "[confirmed] " + tool.key(), response);
				captureEntity(conversation, response);
				return response.withConversation(conversation.getId());
			} catch (Exception e) {
				log.warn("chat_history_persist_failed", e);
			}
		}
		return response;
	}

	private ChatResponse confirmCore(ChatTool tool, Map<String, Object> params, String mode,
			User user, Long conversationId) {
		try {
			JsonNode node = objectMapper.valueToTree(params != null ? params : Map.of());
			ChatResponse response = execute(tool, node, MODE_FORCE.equals(mode), user);
			chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.SUCCESS, "confirm");
			return response;
		}
		catch (ResourceNotFoundException e) {
			chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.FAILED, "ResourceNotFoundException");
			return new ChatResponse("text", "I couldn't find that item.", null);
		}
		catch (Exception e) {
			log.warn("chat_confirm_failed", e);
			chatAuditService.record(user.getId(), conversationId, tool.key(), AiUsageStatus.FAILED, e.getClass().getSimpleName());
			return new ChatResponse("text", "Something went wrong while handling that. Please rephrase and try again.", null);
		}
	}

	/**
	 * Runs one resolved tool. The only entry point that actually writes, shared by the
	 * natural-language path and the structured confirm path so the two cannot drift apart.
	 *
	 * @param force skip the duplicate-expense check — the user already chose "add anyway"
	 */
	private ChatResponse execute(ChatTool tool, JsonNode params, boolean force, User user) {
		return switch (tool) {
			case CREATE_EXPENSE -> handleCreateExpense(params, user, force);
			case UPDATE_EXPENSE -> handleUpdateExpense(params, user);
			case DELETE_EXPENSE -> handleDeleteExpense(params, user);
			case CREATE_BUDGET -> handleCreateBudget(params, user);
			case UPDATE_BUDGET -> handleUpdateBudget(params, user);
			case DELETE_BUDGET -> handleDeleteBudget(params, user);
			case CREATE_GOAL -> handleCreateGoal(params, user);
			case UPDATE_GOAL -> handleUpdateGoal(params, user);
			case DELETE_GOAL -> handleDeleteGoal(params, user);
			case CREATE_RECURRING -> handleCreateRecurring(params, user);
			case UPDATE_RECURRING -> handleUpdateRecurring(params, user);
			case DELETE_RECURRING -> handleDeleteRecurring(params, user);
			case CREATE_CATEGORY -> handleCreateCategory(params, user);
			case RENAME_CATEGORY -> handleRenameCategory(params, user);
			case DELETE_CATEGORY -> handleDeleteCategory(params, user);
			case LIST_CATEGORIES -> handleListCategories(user);
			case UPDATE_PROFILE -> handleUpdateProfile(params, user);
			case GET_SUBSCRIPTION -> handleGetSubscription(user);
			case LIST_GOALS -> handleListGoals(user);
			case LIST_BUDGETS -> handleListBudgets(params, user);
			case LIST_RECURRING -> handleListRecurring(params, user);
			case LIST_ALERTS -> handleListAlerts(params, user);
			case SEARCH_EXPENSES -> handleSearchExpenses(params, user);
			case GET_CATEGORY_TOTALS -> handleGetCategoryTotals(params, user);
			case GET_MONTHLY_REPORT -> handleGetMonthlyReport(params, user);
			case MARK_ALERT_READ -> handleMarkAlertRead(params, user);
			case DISMISS_ALERT -> handleDismissAlert(params, user);
			case DELETE_ALERT -> handleDeleteAlert(params, user);
			case SET_DEFAULT_CATEGORY -> handleSetDefaultCategory(params, user);
			case SET_CATEGORY_ICON -> handleSetCategoryIcon(params, user);
			case DELETE_EXPENSES -> handleDeleteExpenses(params, user);
			case RECATEGORIZE_EXPENSES -> handleRecategorizeExpenses(params, user);
			// Unreachable: TEXT is answered before dispatch reaches here, and every other
			// constant is covered above.
			default -> new ChatResponse("text", params.toString(), null);
		};
	}

	private String resolveModel() {
		return "claude".equalsIgnoreCase(aiProviderProperties.getProvider())
				? claudeProperties.getModel()
				: openAiProperties.getModel();
	}

	private String buildPreviewMessage(ChatTool tool, JsonNode params) {
		return switch (tool) {
			case CREATE_BUDGET -> "Create budget for " + params.path("categoryName").asText("category") + " — ₱" + params.path("amountLimit").asText("0") + "?";
			case CREATE_GOAL -> "Create goal \"" + params.path("name").asText() + "\" — ₱" + params.path("targetAmount").asText("0") + "?";
			case CREATE_RECURRING -> "Create recurring \"" + params.path("name").asText() + "\" — ₱" + params.path("amount").asText("0") + "/" + params.path("frequency").asText("monthly").toLowerCase() + "?";
			case CREATE_EXPENSE -> "Create expense ₱" + params.path("amount").asText("0") + " for " + params.path("description").asText() + "?";
			case CREATE_CATEGORY -> "Create category \"" + params.path("name").asText() + "\"?";
			case DELETE_EXPENSES -> {
				JsonNode ids = params.path("ids");
				if (ids.isArray() && !ids.isEmpty()) {
					yield "Delete " + ids.size() + " expense(s) by ID? This cannot be undone.";
				}
				String cat = params.path("category").asText(null);
				String from = params.path("from").asText(null);
				String to = params.path("to").asText(null);
				String desc = (cat != null ? "category=" + cat : "") + (from != null ? " from=" + from : "") + (to != null ? " to=" + to : "");
				yield "Delete all expenses matching [" + desc.strip() + "]? This cannot be undone.";
			}
			case RECATEGORIZE_EXPENSES -> {
				String from2 = params.path("fromCategory").asText("?");
				String to2 = params.path("toCategory").asText("?");
				yield "Move all expenses from \"" + from2 + "\" to \"" + to2 + "\"? This cannot be undone.";
			}
			default -> "Confirm action?";
		};
	}

	private java.util.Optional<Expense> findRecentDuplicate(User user, BigDecimal amount, String description) {
		LocalDateTime since = LocalDateTime.now().minusDays(7);
		List<Expense> recent = expenseRepository.findByUserAndDateAfterOrderByDateDesc(user, since);
		String needle = description.trim().toLowerCase();
		return recent.stream()
				.filter(e -> e.getAmount().compareTo(amount) == 0
						&& e.getDescription().trim().toLowerCase().equals(needle))
				.findFirst();
	}

	private ChatResponse handleCreateExpense(JsonNode params, User user, boolean confirmed) {
		BigDecimal amount = params.get("amount").decimalValue();
		String category = params.path("category").asText("Uncategorized");
		String description = params.get("description").asText();
		String dateStr = params.path("date").asText(null);
		LocalDateTime date = (dateStr != null && !dateStr.isBlank())
				? LocalDate.parse(dateStr).atStartOfDay()
				: null;

		java.util.Optional<Expense> dupe = confirmed ? java.util.Optional.empty() : findRecentDuplicate(user, amount, description);
		if (dupe.isPresent()) {
			Expense e = dupe.get();
			String dupeDate = e.getDate() != null ? e.getDate().toLocalDate().toString() : "recently";
			String dupeAmt = "₱" + e.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
			Map<String, Object> confirmData = new LinkedHashMap<>();
			confirmData.put("toolName", ChatTool.CREATE_EXPENSE.key());
			confirmData.put("params", objectMapper.convertValue(params, Map.class));
			confirmData.put("existingId", e.getId());
			return new ChatResponse("disambiguate",
					"Looks like a duplicate of \"" + e.getDescription() + "\" (" + dupeAmt + " on " + dupeDate + ") — add anyway?",
					confirmData);
		}

		// QUICK_ADD, not MANUAL: the amount and the merchant came out of a parser reading free
		// text, which is the same provenance as /expenses/quick-add and carries the same caveat.
		ExpenseRequest req = new ExpenseRequest(amount, category, date, description, null, null, null, null);
		Object result = expenseService.create(req, user, ExpenseSource.QUICK_ADD);
		return new ChatResponse("action", "Expense created: ₱" + amount.toPlainString() + " — " + description, result);
	}

	/**
	 * Edit an expense from chat.
	 *
	 * <p>{@code ExpenseService.update} takes PUT semantics — the request states the whole expense,
	 * so an absent currency means PHP at rate 1 and an absent tag means the expense has none. That
	 * is right for {@code PUT /expenses/{id}}, whose caller sends the whole row, and wrong here:
	 * the {@code update_expense} tool schema has no currency, exchange-rate or project property at
	 * all, so this path can only ever be silent about them, never deliberate. Left as it was, one
	 * "change the description" redenominated a foreign-currency expense to PHP at rate 1 and
	 * dropped its project tag.
	 *
	 * <p>Fixed on this side rather than by null-guarding {@code update}: a guard there would make
	 * the REST PUT unable to express "this expense is PHP again" or "remove the tag", which is a
	 * contract change to a published endpoint. The caller is the one with the missing information,
	 * so the caller reads the row and re-states what the user did not ask to change.
	 *
	 * <p>The category is the same defect one field over: the tool schema requires only {@code id},
	 * {@code amount} and {@code description}, so "rename that dinner" arrives with no category and
	 * reading it as {@code Uncategorized} stated a category the user never asked for. It too is
	 * re-stated from the row — with the caveat {@link #restoreCategoryOverride} explains, because
	 * {@code update} re-runs {@code categorise} over whatever category it is handed.
	 *
	 * <p>The whole body runs in {@link #inOneTransaction} because re-stating the row is a
	 * read-then-write: the read that decides what to carry forward and the write that applies it
	 * have to see one state of the row, or the values carried forward are the ones from a moment
	 * ago rather than the ones being written over. Inside the single transaction the entity read
	 * here and the entity {@code update} loads are the same managed instance, and the whole edit
	 * commits once (TEN-323).
	 */
	private ChatResponse handleUpdateExpense(JsonNode params, User user) {
		return inOneTransaction(() -> updateExpense(params, user));
	}

	private ChatResponse updateExpense(JsonNode params, User user) {
		long id = params.get("id").asLong();
		BigDecimal amount = params.get("amount").decimalValue();
		String statedCategory = params.path("category").asText(null);
		boolean categoryStated = statedCategory != null && !statedCategory.isBlank();
		String description = params.get("description").asText();
		String dateStr = params.path("date").asText(null);
		LocalDateTime date = (dateStr != null && !dateStr.isBlank())
				? LocalDate.parse(dateStr).atStartOfDay()
				: null;
		// Same lookup rule update() itself applies, so an ADMIN reaching another person's row
		// reads the values it is about to re-state, and anyone else still gets the 404.
		Expense existing = (user.isAdmin()
				? expenseRepository.findById(id)
				: expenseRepository.findByIdAndUser(id, user))
				.orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
		String category = categoryStated
				? statedCategory
				: (existing.getCategory() != null ? existing.getCategory().getName() : null);
		boolean overriddenBefore = existing.isCategoryOverridden();
		ExpenseRequest req = new ExpenseRequest(amount, category, date, description,
				existing.getExpenseType() != null ? existing.getExpenseType().name() : null,
				existing.isReimbursable(),
				existing.getCurrency(),
				existing.getExchangeRate(),
				null,
				existing.getProject() != null ? existing.getProject().getName() : null);
		ExpenseResponse result = expenseService.update(id, req, user);
		if (!categoryStated) {
			// The restore corrects a flag update() has just written, and shares the caller's
			// transaction (TEN-322): a commit in between would publish a categoryOverridden the user
			// never asked for — and leave it there permanently if the request died in the window.
			restoreCategoryOverride(id, overriddenBefore);
		}
		return new ChatResponse("action", "Expense #" + id + " updated.", result);
	}

	/**
	 * Run a read-then-write chat handler as a single transaction.
	 *
	 * <p>Applied to the four update handlers that resolve an entity and then hand its values to a
	 * service that is itself {@code @Transactional} — expense, budget, goal and recurring. Each read
	 * the row in one transaction and wrote in another, so the values re-stated from the read could
	 * already be stale by the time the write ran (TEN-323).
	 *
	 * <p>Also applied to {@code handleSetCategoryIcon}, which carries the resolved category's name
	 * forward into its write, and to {@code handleRecategorizeExpenses}, whose wrapper contains the
	 * category {@code getOrCreateByName} creates so a later {@code saveAll} failure cannot orphan
	 * it. Neither buys lost-update detection; see the residual below.
	 *
	 * <p>TEN-324 then covered the rest of the handlers that write more than once:
	 * {@code handleDeleteExpenses}, whose per-id loop could half-finish and still report a count;
	 * {@code handleCreateBudget} and {@code handleSetDefaultCategory}, whose
	 * {@code getOrCreateByName} could strand an empty category if the write after it failed; and
	 * {@code handleRenameCategory} and {@code handleDeleteCategory}, which needed their catch
	 * hoisted outside the wrapper first — see the rollback-only note below. The same issue removed
	 * the inert {@code @Transactional} annotations the class used to carry, so wrapping here is now
	 * the class's only convention and no annotation implies a guarantee it does not provide.
	 *
	 * <p>The handlers still outside a wrapper are ruled out rather than overlooked:
	 *
	 * <ul>
	 *   <li><b>Creates</b> other than the budget one carry nothing from a read into a write.
	 *       {@code handleCreateExpense} does read, via {@code findRecentDuplicate}, but only to
	 *       gate — and wrapping would not stop a concurrent duplicate insert under
	 *       {@code READ COMMITTED}, which needs a unique constraint.
	 *   <li><b>The single-row deletes</b> resolve a row first but carry nothing forward that changes
	 *       the outcome: a concurrent edit changes which values the row held, not which row the user
	 *       named, and the row is deleted either way. The confirmation message may quote pre-edit
	 *       values.
	 *   <li><b>The read tools</b> perform no write, so there is no read-then-write to protect, and
	 *       they carry no annotation claiming otherwise. {@code handleListAlerts} is the exception
	 *       that proves the rule: {@code alertService.getOrGenerate} does persist, and is safe
	 *       because {@code AlertService} carries a live cross-bean {@code @Transactional} of its own
	 *       — as do the three alert mutations, each a single call into it.
	 *   <li><b>{@code handleUpdateProfile}</b> has no read of its own to pair with a write, so a
	 *       transaction here would fix nothing. It used to re-state from the authenticated
	 *       {@code User}, a snapshot resolved before the LLM round-trip — a <em>wider</em>
	 *       staleness window than the one closed here, not an absent one. Fixed under TEN-325 by
	 *       writing only the fields the tool call named, through
	 *       {@code UserProfileService.patchProfile}, rather than by wrapping anything here.
	 * </ul>
	 *
	 * <p><b>The rollback-only hazard.</b> An inner {@code @Transactional} that throws marks the
	 * shared transaction rollback-only, so a handler that catches that exception <em>inside</em> the
	 * callback and returns a friendly message fails the outer commit with
	 * {@code UnexpectedRollbackException}. Hoisting the catch outside {@code inOneTransaction} is
	 * the fix, not an abstention: {@code TransactionTemplate} rolls back and rethrows the original
	 * exception rather than reaching commit, so the friendly message survives and the rolled-back
	 * writes are genuinely gone. Where the friendly path must not be reached by an exception at all
	 * — {@code handleDeleteExpenses} skipping an id that does not resolve — the condition is checked
	 * before the call instead.
	 *
	 * <p>Programmatic rather than {@code @Transactional}, and per handler rather than on
	 * {@link #execute}: these methods are private and every route to them is a same-bean call, which
	 * Spring's proxy does not intercept, so an annotation here would do nothing. The public entry
	 * point that <em>would</em> be intercepted wraps the LLM classification call, and holding a
	 * database connection open across a model round-trip is worse than the race it would close. Same
	 * idiom as {@code PaymentService}.
	 *
	 * <p>What this does not do is detect a lost update at the row level: with no {@code @Version} on
	 * the entities, two transactions that read and write concurrently still resolve last-writer-wins
	 * under {@code READ COMMITTED}. That is true of {@code PUT /expenses/{id}} too, so the chat path
	 * is now no weaker than the REST one; closing it for both is an entity change, not a change here.
	 */
	private ChatResponse inOneTransaction(Supplier<ChatResponse> handler) {
		return new TransactionTemplate(transactionManager).execute(status -> handler.get());
	}

	/**
	 * Put back the {@code categoryOverridden} flag on an edit that never mentioned a category.
	 *
	 * <p>{@code ExpenseService.categorise} reads an explicit category as a hand-categorisation and
	 * decides the flag from it: set when the category contradicts the merchant rule matching the
	 * description, cleared (and the rule taught) when it does not. That is right when the user named
	 * a category — the case above still goes through it untouched, so naming one still learns the
	 * rule and still records an override. It is wrong for a category this handler re-stated only to
	 * keep it: renaming "Dinner" to "Grab ride" would then flag the row as a deliberate override of
	 * a rule the user never argued with. The flag is not on {@code ExpenseRequest}, so it cannot be
	 * re-stated with the rest of the row and is put back here instead.
	 *
	 * <p>It is not on {@code ExpenseResponse} either, so the response already returned is accurate.
	 *
	 * <p>The merchant rule {@code categorise} may learn from the re-stated category is deliberately
	 * left alone: that is exactly what {@code PUT /expenses/{id}} does when a client re-states the
	 * category it already had, and teaching the rule the category the row genuinely has is a far
	 * smaller claim than the {@code Uncategorized} this path used to teach.
	 *
	 * <p>Called only from the one place above, inside its transaction and after its owner-scoped
	 * lookup has already thrown for a caller who may not reach this row — which is why the re-read
	 * here does not repeat that check. Do not call it from anywhere that has not made it.
	 */
	private void restoreCategoryOverride(long id, boolean overridden) {
		expenseRepository.findById(id).ifPresent(expense -> {
			if (expense.isCategoryOverridden() != overridden) {
				expense.setCategoryOverridden(overridden);
				expenseRepository.save(expense);
			}
		});
	}

	private ChatResponse handleDeleteExpense(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			expenseService.delete(id, user);
			return new ChatResponse("action", "Expense #" + id + " has been deleted.", null);
		}
		if (params.path("latest").asBoolean(false)) {
			Expense latest = expenseRepository.findTopByUserOrderByDateDesc(user)
					.orElseThrow(() -> new ResourceNotFoundException("No expenses found."));
			String desc = latest.getDescription();
			String amount = "₱" + latest.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
			expenseService.delete(latest.getId(), user);
			return new ChatResponse("action", "Deleted \"" + desc + "\" (" + amount + ") from your expenses.", null);
		}
		String keyword = params.path("description").asText(null);
		if (keyword != null && !keyword.isBlank()) {
			List<Expense> matches = expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, keyword);
			if (matches.isEmpty()) {
				return new ChatResponse("text", "No expense found matching \"" + keyword + "\". Double-check the name and try again.", null);
			}
			if (matches.size() > 1) {
				List<Map<String, Object>> items = matches.stream().map(e -> {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("id", e.getId());
					item.put("description", e.getDescription());
					item.put("amount", e.getAmount());
					item.put("date", e.getDate() != null ? e.getDate().toLocalDate().toString() : "");
					return item;
				}).toList();
				return new ChatResponse("disambiguate", "Found " + matches.size() + " expenses matching \"" + keyword + "\". Which one would you like to delete?", items);
			}
			Expense toDelete = matches.get(0);
			String amount = "₱" + toDelete.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
			expenseService.delete(toDelete.getId(), user);
			return new ChatResponse("action", "Deleted \"" + toDelete.getDescription() + "\" (" + amount + ") from your expenses.", null);
		}
		return new ChatResponse("text", "Which expense would you like to delete? You can say 'delete latest expense', 'delete [description]', or provide an ID.", null);
	}

	/**
	 * Write-then-write: {@code getOrCreateByName} is a live cross-bean {@code @Transactional} that
	 * used to commit a new category in its own transaction, stranding an empty one if the budget
	 * create that follows failed. Both writes now share one transaction (TEN-324).
	 *
	 * <p>The conflict catch is hoisted outside the wrapper for the reason given on
	 * {@link #handleRenameCategory}: {@code BudgetService.create} throws its
	 * {@code ResponseStatusException} from inside a live {@code @Transactional}, so catching it in
	 * the callback would mark the shared transaction rollback-only and the preview could never
	 * commit. Out here the transaction is already rolled back — which is also what makes the
	 * category created on the failed attempt disappear rather than linger — and the branch below
	 * re-resolves it in its own transaction to build the preview.
	 */
	private ChatResponse handleCreateBudget(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		String month = params.path("month").asText(YearMonth.now().toString());
		BigDecimal amountLimit = params.get("amountLimit").decimalValue();
		try {
			return inOneTransaction(() -> {
				Category cat = categoryService.getOrCreateByName(categoryName, user);
				BudgetRequest req = new BudgetRequest(cat.getId(), month, amountLimit, null, null, null);
				Object result = budgetService.create(req, user);
				return new ChatResponse("action", "Budget created for " + categoryName + " (₱" + amountLimit.toPlainString() + ").", result);
			});
		} catch (ResponseStatusException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				Category cat = categoryService.getOrCreateByName(categoryName, user);
				List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);
				Budget existing = budgets.stream()
						.filter(b -> b.getCategory().getId().equals(cat.getId()))
						.findFirst().orElse(null);
				if (existing != null) {
					BigDecimal currentAmount = existing.getAmountLimit().setScale(2, java.math.RoundingMode.HALF_UP);
					BigDecimal newAmount = amountLimit.setScale(2, java.math.RoundingMode.HALF_UP);
					Map<String, Object> previewParams = new LinkedHashMap<>();
					previewParams.put("id", existing.getId());
					previewParams.put("categoryId", existing.getCategory().getId());
					previewParams.put("categoryName", categoryName);
					previewParams.put("month", month);
					previewParams.put("currentAmount", currentAmount);
					previewParams.put("amountLimit", newAmount);
					Map<String, Object> previewData = new LinkedHashMap<>();
					previewData.put("toolName", ChatTool.UPDATE_BUDGET.key());
					previewData.put("params", previewParams);
					return new ChatResponse("preview",
							"A budget for " + categoryName + " already exists this month (₱" + currentAmount.toPlainString() +
							"). Would you like to update it to ₱" + newAmount.toPlainString() + "?",
							previewData);
				}
				return new ChatResponse("text", "A budget for " + categoryName + " already exists for " + month + ".", null);
			}
			throw e;
		}
	}

	private ChatResponse handleDeleteBudget(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			budgetService.delete(id, user);
			return new ChatResponse("action", "Budget #" + id + " deleted.", null);
		}
		String categoryName = params.path("categoryName").asText(null);
		String month = params.path("month").asText(YearMonth.now().toString());
		if (categoryName != null && !categoryName.isBlank()) {
			List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);
			Budget match = budgets.stream()
					.filter(b -> b.getCategory().getName().equalsIgnoreCase(categoryName))
					.findFirst()
					.orElse(null);
			if (match == null) {
				return new ChatResponse("text", "No budget found for " + categoryName + " in " + month + ".", null);
			}
			budgetService.delete(match.getId(), user);
			return new ChatResponse("action", "Deleted budget for " + categoryName + " (" + month + ").", null);
		}
		return new ChatResponse("text", "Please specify a budget ID or category name.", null);
	}

	private ChatResponse handleCreateGoal(JsonNode params, User user) {
		String name = params.get("name").asText();
		BigDecimal targetAmount = params.get("targetAmount").decimalValue();
		BigDecimal savedAmount = params.path("savedAmount").isMissingNode()
				? BigDecimal.ZERO
				: params.path("savedAmount").decimalValue();
		String targetDateStr = params.path("targetDate").asText(null);
		LocalDate targetDate = (targetDateStr != null && !targetDateStr.isBlank())
				? LocalDate.parse(targetDateStr)
				: null;
		GoalRequest req = new GoalRequest(name, targetAmount, savedAmount, targetDate, false, null);
		try {
			Object result = savingsGoalService.create(req, user, false);
			return new ChatResponse("action", "Goal created: " + name + " (₱" + targetAmount.toPlainString() + ").", result);
		} catch (ResponseStatusException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				SavingsGoal existing = savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
						.filter(g -> g.getName().equalsIgnoreCase(name))
						.findFirst().orElse(null);
				Map<String, Object> previewParams = new LinkedHashMap<>();
				previewParams.put("name", name);
				previewParams.put("targetAmount", targetAmount.setScale(2, RoundingMode.HALF_UP));
				if (existing != null) {
					previewParams.put("id", existing.getId());
					previewParams.put("currentTarget", existing.getTargetAmount().setScale(2, RoundingMode.HALF_UP));
				}
				Map<String, Object> previewData = new LinkedHashMap<>();
				previewData.put("toolName", ChatTool.UPDATE_GOAL.key());
				previewData.put("params", previewParams);
				return new ChatResponse("preview",
						"A goal named \"" + name + "\" already exists. Would you like to update it to ₱" +
						targetAmount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "?",
						previewData);
			}
			throw e;
		}
	}

	private ChatResponse handleDeleteGoal(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			savingsGoalService.delete(id, user);
			return new ChatResponse("action", "Goal #" + id + " deleted.", null);
		}
		String name = params.path("name").asText(null);
		if (name != null && !name.isBlank()) {
			List<SavingsGoal> goals = savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user);
			SavingsGoal match = goals.stream()
					.filter(g -> g.getName().equalsIgnoreCase(name))
					.findFirst()
					.orElse(null);
			if (match == null) {
				return new ChatResponse("text", "No goal found named \"" + name + "\".", null);
			}
			savingsGoalService.delete(match.getId(), user);
			return new ChatResponse("action", "Deleted goal: \"" + match.getName() + "\".", null);
		}
		return new ChatResponse("text", "Please specify a goal ID or name.", null);
	}

	private ChatResponse handleCreateRecurring(JsonNode params, User user) {
		String name = params.get("name").asText();
		BigDecimal amount = params.get("amount").decimalValue();
		Frequency frequency = Frequency.valueOf(params.get("frequency").asText());
		String categoryName = params.path("categoryName").asText(null);
		Integer dayOfMonth = params.path("dayOfMonth").isMissingNode() ? null : params.path("dayOfMonth").asInt();
		Integer dayOfWeek = params.path("dayOfWeek").isMissingNode() ? null : params.path("dayOfWeek").asInt();
		RecurringExpenseRequest req = new RecurringExpenseRequest(name, amount, categoryName, frequency, dayOfMonth, dayOfWeek, null, null, null, null);
		try {
			Object result = recurringExpenseService.create(req, user, false);
			return new ChatResponse("action", "Recurring expense created: " + name + " (₱" + amount.toPlainString() + ").", result);
		} catch (ResponseStatusException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				RecurringExpense existing = recurringExpenseRepository.findAllByUser(user).stream()
						.filter(r -> r.getName().equalsIgnoreCase(name) && r.getFrequency() == frequency)
						.findFirst().orElse(null);
				Map<String, Object> previewParams = new LinkedHashMap<>();
				previewParams.put("name", name);
				previewParams.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
				previewParams.put("frequency", frequency.name());
				if (existing != null) {
					previewParams.put("id", existing.getId());
					previewParams.put("currentAmount", existing.getAmount().setScale(2, RoundingMode.HALF_UP));
				}
				Map<String, Object> previewData = new LinkedHashMap<>();
				previewData.put("toolName", ChatTool.UPDATE_RECURRING.key());
				previewData.put("params", previewParams);
				return new ChatResponse("preview",
						"A " + frequency.name().toLowerCase() + " recurring expense named \"" + name + "\" already exists. Would you like to update it to ₱" +
						amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "?",
						previewData);
			}
			throw e;
		}
	}

	private String recurringLabel(RecurringExpense r) {
		String freq = r.getFrequency() != null ? r.getFrequency().name().charAt(0) + r.getFrequency().name().substring(1).toLowerCase() : "";
		String amount = "₱" + r.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
		return "\"" + r.getName() + "\" (" + amount + "/" + freq + ")";
	}

	private ChatResponse handleDeleteRecurring(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			recurringExpenseService.delete(id, user);
			return new ChatResponse("action", "Recurring expense #" + id + " has been removed.", null);
		}
		if (params.path("latest").asBoolean(false)) {
			List<RecurringExpense> all = recurringExpenseRepository.findAllByUser(user);
			if (all.isEmpty()) {
				return new ChatResponse("text", "You don't have any recurring expenses set up yet.", null);
			}
			RecurringExpense latest = all.stream()
					.max(java.util.Comparator.comparing(RecurringExpense::getCreatedAt))
					.orElseThrow();
			String label = recurringLabel(latest);
			recurringExpenseService.delete(latest.getId(), user);
			return new ChatResponse("action", label + " has been removed from your recurring expenses.", null);
		}
		String name = params.path("name").asText(null);
		if (name != null && !name.isBlank()) {
			List<RecurringExpense> all = recurringExpenseRepository.findAllByUser(user);
			List<RecurringExpense> matches = all.stream()
					.filter(r -> r.getName().toLowerCase().contains(name.toLowerCase()))
					.toList();
			if (matches.isEmpty()) {
				return new ChatResponse("text", "No recurring expense found matching \"" + name + "\". Check the name and try again.", null);
			}
			if (matches.size() > 1) {
				return new ChatResponse("text", "Found " + matches.size() + " recurring expenses matching \"" + name + "\". Be more specific — try using the full name.", null);
			}
			String label = recurringLabel(matches.get(0));
			recurringExpenseService.delete(matches.get(0).getId(), user);
			return new ChatResponse("action", label + " has been removed from your recurring expenses.", null);
		}
		return new ChatResponse("text", "Which recurring expense would you like to remove? You can say 'delete latest recurring', 'delete recurring [name]', or provide an ID.", null);
	}

	/** Reads a budget (by id, or by category and month) and then writes it — see {@link #inOneTransaction}. */
	private ChatResponse handleUpdateBudget(JsonNode params, User user) {
		return inOneTransaction(() -> updateBudget(params, user));
	}

	private ChatResponse updateBudget(JsonNode params, User user) {
		long id = params.path("id").asLong(0);
		BigDecimal amountLimit = params.get("amountLimit").decimalValue();
		String month = params.path("month").asText(YearMonth.now().toString());
		String categoryName = params.path("categoryName").asText(null);

		if (id > 0) {
			Budget budget = budgetRepository.findByIdAndUser(id, user)
					.orElseThrow(() -> new ResourceNotFoundException("Budget not found: " + id));
			BudgetRequest req = new BudgetRequest(budget.getCategory().getId(), budget.getMonth(), amountLimit, null, null, null);
			Object result = budgetService.update(id, req, user);
			return new ChatResponse("action", "Budget updated to ₱" + amountLimit.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".", result);
		}

		if (categoryName != null && !categoryName.isBlank()) {
			Category cat = categoryService.getOrCreateByName(categoryName, user);
			List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);
			Budget match = budgets.stream()
					.filter(b -> b.getCategory().getId().equals(cat.getId()))
					.findFirst()
					.orElse(null);
			if (match == null) {
				return new ChatResponse("text", "No budget found for " + categoryName + " in " + month + ".", null);
			}
			BudgetRequest req = new BudgetRequest(match.getCategory().getId(), month, amountLimit, null, null, null);
			Object result = budgetService.update(match.getId(), req, user);
			return new ChatResponse("action", "Budget for " + categoryName + " updated to ₱" + amountLimit.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".", result);
		}

		return new ChatResponse("text", "Please specify a budget ID or category name to update.", null);
	}

	/** Resolves a goal and then writes it — see {@link #inOneTransaction}. */
	private ChatResponse handleUpdateGoal(JsonNode params, User user) {
		return inOneTransaction(() -> updateGoal(params, user));
	}

	private ChatResponse updateGoal(JsonNode params, User user) {
		SavingsGoal goal = resolveGoal(params, user);
		if (goal == null) {
			return new ChatResponse("text", "No goal found. Please specify a goal ID or name.", null);
		}

		BigDecimal targetAmount = params.path("targetAmount").isMissingNode()
				? goal.getTargetAmount()
				: params.path("targetAmount").decimalValue();
		BigDecimal savedAmount = params.path("savedAmount").isMissingNode()
				? goal.getSavedAmount()
				: params.path("savedAmount").decimalValue();
		String targetDateStr = params.path("targetDate").asText(null);
		LocalDate targetDate = (targetDateStr != null && !targetDateStr.isBlank())
				? LocalDate.parse(targetDateStr)
				: goal.getTargetDate();
		boolean paused = params.path("paused").isMissingNode()
				? goal.isPaused()
				: params.path("paused").asBoolean();

		GoalRequest req = new GoalRequest(goal.getName(), targetAmount, savedAmount, targetDate, paused, goal.getCurrency());
		Object result = savingsGoalService.update(goal.getId(), req, user);
		return new ChatResponse("action", "Goal \"" + goal.getName() + "\" updated.", result);
	}

	private SavingsGoal resolveGoal(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			return savingsGoalRepository.findByIdAndUser(id, user).orElse(null);
		}
		String name = params.path("name").asText(null);
		if (name != null && !name.isBlank()) {
			return savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
					.filter(g -> g.getName().equalsIgnoreCase(name))
					.findFirst().orElse(null);
		}
		return null;
	}

	/** Resolves a recurring expense and then writes it — see {@link #inOneTransaction}. */
	private ChatResponse handleUpdateRecurring(JsonNode params, User user) {
		return inOneTransaction(() -> updateRecurring(params, user));
	}

	private ChatResponse updateRecurring(JsonNode params, User user) {
		RecurringExpense recurring = resolveRecurring(params, user);
		if (recurring == null) {
			return new ChatResponse("text", "No recurring expense found. Please specify an ID or name.", null);
		}

		BigDecimal amount = params.path("amount").isMissingNode()
				? recurring.getAmount()
				: params.path("amount").decimalValue();
		String frequencyStr = params.path("frequency").asText(null);
		Frequency frequency = frequencyStr != null ? Frequency.valueOf(frequencyStr) : recurring.getFrequency();
		Integer dayOfMonth = params.path("dayOfMonth").isMissingNode()
				? recurring.getDayOfMonth()
				: Integer.valueOf(params.path("dayOfMonth").asInt());
		Integer dayOfWeek = params.path("dayOfWeek").isMissingNode()
				? recurring.getDayOfWeek()
				: Integer.valueOf(params.path("dayOfWeek").asInt());
		Boolean active = params.path("active").isMissingNode()
				? recurring.isActive()
				: params.path("active").asBoolean();
		String categoryName = recurring.getCategory() != null ? recurring.getCategory().getName() : "Uncategorized";

		RecurringExpenseRequest req = new RecurringExpenseRequest(
				recurring.getName(), amount, categoryName, frequency,
				dayOfMonth, dayOfWeek, recurring.getMonthOfYear(), active,
				recurring.getCurrency(), recurring.getExchangeRate());
		Object result = recurringExpenseService.update(recurring.getId(), req, user);
		return new ChatResponse("action", "Recurring expense \"" + recurring.getName() + "\" updated.", result);
	}

	private RecurringExpense resolveRecurring(JsonNode params, User user) {
		if (params.has("id") && !params.get("id").isNull() && params.get("id").asLong(0) > 0) {
			long id = params.get("id").asLong();
			return recurringExpenseRepository.findByIdAndUser(id, user).orElse(null);
		}
		String name = params.path("name").asText(null);
		if (name != null && !name.isBlank()) {
			List<RecurringExpense> matches = recurringExpenseRepository.findAllByUser(user).stream()
					.filter(r -> r.getName().equalsIgnoreCase(name))
					.toList();
			return matches.size() == 1 ? matches.get(0) : null;
		}
		return null;
	}

	private ChatResponse handleCreateCategory(JsonNode params, User user) {
		String name = params.get("name").asText();
		String icon = params.path("icon").asText(null);
		try {
			CategoryResponse result = categoryService.create(new CategoryRequest(name, icon), user);
			return new ChatResponse("action", "Category \"" + name + "\" created.", result);
		} catch (IllegalArgumentException e) {
			return new ChatResponse("text", e.getMessage(), null);
		}
	}

	/**
	 * Reads the category and renames it in one transaction, with the catch hoisted <em>outside</em>
	 * the wrapper (TEN-324). {@code CategoryService.update} is a live cross-bean
	 * {@code @Transactional}: catching its duplicate-name {@code IllegalArgumentException} inside the
	 * callback would mark the shared transaction rollback-only and turn the friendly message into an
	 * {@code UnexpectedRollbackException} at commit. Caught out here instead,
	 * {@code TransactionTemplate} has already rolled back and rethrown the original exception, so the
	 * friendly message survives.
	 */
	private ChatResponse handleRenameCategory(JsonNode params, User user) {
		try {
			return inOneTransaction(() -> renameCategory(params, user));
		} catch (IllegalArgumentException e) {
			return new ChatResponse("text", e.getMessage(), null);
		}
	}

	private ChatResponse renameCategory(JsonNode params, User user) {
		String currentName = params.get("currentName").asText();
		String newName = params.get("newName").asText();
		if ("Uncategorized".equalsIgnoreCase(currentName)) {
			return new ChatResponse("text", "The \"Uncategorized\" category cannot be renamed.", null);
		}
		var all = categoryService.findAll(user);
		var match = all.stream()
				.filter(c -> c.name().equalsIgnoreCase(currentName))
				.findFirst().orElse(null);
		if (match == null) {
			return new ChatResponse("text", "No category named \"" + currentName + "\" found.", null);
		}
		CategoryResponse result = categoryService.update(match.id(), new CategoryRequest(newName, match.icon()), user);
		return new ChatResponse("action", "Category \"" + currentName + "\" renamed to \"" + newName + "\".", result);
	}

	/** Same shape and the same hoisted catch as {@link #handleRenameCategory}. */
	private ChatResponse handleDeleteCategory(JsonNode params, User user) {
		try {
			return inOneTransaction(() -> deleteCategory(params, user));
		} catch (IllegalArgumentException e) {
			return new ChatResponse("text", e.getMessage(), null);
		}
	}

	private ChatResponse deleteCategory(JsonNode params, User user) {
		String name = params.get("name").asText();
		if ("Uncategorized".equalsIgnoreCase(name)) {
			return new ChatResponse("text", "The \"Uncategorized\" category cannot be deleted.", null);
		}
		var all = categoryService.findAll(user);
		var match = all.stream()
				.filter(c -> c.name().equalsIgnoreCase(name))
				.findFirst().orElse(null);
		if (match == null) {
			return new ChatResponse("text", "No category named \"" + name + "\" found.", null);
		}
		categoryService.delete(match.id(), user);
		return new ChatResponse("action", "Category \"" + name + "\" deleted. Affected expenses moved to Uncategorized.", null);
	}

	private ChatResponse handleListCategories(User user) {
		List<CategoryResponse> categories = categoryService.findAll(user);
		return new ChatResponse("action", "You have " + categories.size() + " categories.", categories);
	}

	/**
	 * Writes only the fields the tool call named (TEN-325).
	 *
	 * <p>This used to restate every profile field from {@code user} and send them through the
	 * whole-object {@code updateProfile}. {@code user} is the detached principal
	 * {@code JwtAuthFilter} resolves once per request, before the LLM round-trip, so a field
	 * changed from another device while the model was thinking was silently reverted by a chat
	 * turn that never mentioned it. The staleness window spanned the whole request — wider than
	 * the read-then-write window TEN-323 closed, and not the same shape: there is no read here to
	 * pair with the write, so a transaction would have fixed nothing.
	 *
	 * <p>The fix is {@link UserProfileService#patchProfile}: unnamed fields are not written at
	 * all, so there is nothing stale to write back, and the row is re-read inside the write
	 * transaction. Only {@code user.getEmail()} is still taken from the principal — it identifies
	 * which row to read, it is what the token was issued for, and it is not a field this path
	 * writes.
	 */
	private ChatResponse handleUpdateProfile(JsonNode params, User user) {
		String name = params.path("name").asText(null);
		String nickname = params.path("nickname").asText(null);
		String avatar = params.path("avatar").asText(null);
		String defaultCategory = params.path("defaultCategory").asText(null);

		UserProfileService.ProfilePatch patch = new UserProfileService.ProfilePatch(
				named(name),
				nickname != null ? Optional.of(nickname) : Optional.empty(),
				named(defaultCategory),
				named(avatar));

		Object result = userProfileService.patchProfile(user.getEmail(), patch);
		return new ChatResponse("action", "Profile updated.", result);
	}

	/**
	 * A tool-call argument counts as named only when it is present and not blank — the model emits
	 * an empty string for a field it has nothing to say about. {@code nickname} is the exception
	 * and is handled at the call site: a blank one there is a deliberate clear, which is how the
	 * whole-object path behaved too.
	 */
	private static Optional<String> named(String value) {
		return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
	}

	private ChatResponse handleGetSubscription(User user) {
		EntitlementService.Entitlements entitlements = entitlementService.describe(user);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("plan", entitlements.plan().name());
		data.put("status", entitlements.status().name());
		data.put("features", entitlements.features().stream().map(f -> f.name()).toList());
		data.put("admin", entitlements.admin());
		String planLabel = switch (entitlements.plan()) {
			case PREMIUM -> "Premium";
			case TRIAL -> "Trial";
			default -> "Free";
		};
		return new ChatResponse("action", "You are on the " + planLabel + " plan (" + entitlements.status().name().toLowerCase() + ").", data);
	}

	private ChatResponse handleListGoals(User user) {
		List<GoalResponse> goals = savingsGoalService.findAll(user);
		List<Map<String, Object>> items = goals.stream().map(g -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", g.id());
			item.put("name", g.name());
			item.put("targetAmount", g.targetAmount());
			item.put("savedAmount", g.savedAmount());
			item.put("progressPercent", g.progressPercent());
			item.put("status", g.status().name());
			if (g.targetDate() != null) {
				item.put("targetDate", g.targetDate().toString());
			}
			return item;
		}).toList();
		return new ChatResponse("action", "You have " + items.size() + " savings goal(s).", items);
	}

	private ChatResponse handleListBudgets(JsonNode params, User user) {
		String month = params.path("month").asText(YearMonth.now().toString());
		BudgetSummaryResponse summary = budgetService.getSummary(month, user);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("month", summary.month());
		result.put("totalBudgeted", summary.totalBudgeted());
		result.put("totalSpent", summary.totalSpent());
		result.put("safeToSpend", summary.safeToSpend());
		List<Map<String, Object>> items = summary.items().stream().map(i -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("categoryName", i.categoryName());
			item.put("budgeted", i.budgeted());
			item.put("spent", i.spent());
			item.put("remaining", i.remaining());
			item.put("percentUsed", i.percentUsed());
			item.put("status", i.status());
			return item;
		}).toList();
		result.put("items", items);
		return new ChatResponse("action", "Budget summary for " + month + ": ₱" + summary.totalSpent().toPlainString() + " spent of ₱" + summary.totalBudgeted().toPlainString() + " budgeted.", result);
	}

	private ChatResponse handleListRecurring(JsonNode params, User user) {
		String month = params.path("month").asText(YearMonth.now().toString());
		List<RecurringExpenseResponse> recurring = recurringExpenseService.findAll(user);
		List<UpcomingBillResponse> upcoming = recurringExpenseService.getUpcoming(month, user);

		List<Map<String, Object>> recurringItems = recurring.stream().map(r -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", r.id());
			item.put("name", r.name());
			item.put("amount", r.amount());
			item.put("categoryName", r.categoryName());
			item.put("frequency", r.frequency().name());
			item.put("active", r.active());
			return item;
		}).toList();

		List<Map<String, Object>> upcomingItems = upcoming.stream().map(u -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", u.name());
			item.put("amount", u.amount());
			item.put("dueDate", u.dueDate());
			return item;
		}).toList();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", recurringItems);
		result.put("upcoming", upcomingItems);
		return new ChatResponse("action", "You have " + recurring.size() + " recurring expense(s), " + upcomingItems.size() + " upcoming.", result);
	}

	private ChatResponse handleListAlerts(JsonNode params, User user) {
		String month = params.path("month").asText(YearMonth.now().toString());
		List<AlertResponse> alerts = alertService.getOrGenerate(user, month);
		List<Map<String, Object>> items = alerts.stream().map(a -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", a.id());
			item.put("type", a.type().name());
			item.put("severity", a.severity().name());
			item.put("message", a.message());
			item.put("read", a.read());
			return item;
		}).toList();
		return new ChatResponse("action", "You have " + items.size() + " active alert(s) for " + month + ".", items);
	}

	private ChatResponse handleSearchExpenses(JsonNode params, User user) {
		String fromStr = params.path("from").asText(null);
		String toStr = params.path("to").asText(null);
		LocalDate from = (fromStr != null && !fromStr.isBlank()) ? LocalDate.parse(fromStr) : null;
		LocalDate to = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr) : null;

		List<Expense> raw;
		if (from != null && to != null) {
			raw = expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
					user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
		} else if (from != null) {
			raw = expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(user, from.atStartOfDay());
		} else if (to != null) {
			raw = expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(user, to.plusDays(1).atStartOfDay());
		} else {
			raw = expenseRepository.findAllByUserOrderByDateDesc(user);
		}

		String categoryFilter = params.path("category").asText(null);
		String vendorFilter = params.path("vendor").asText(null);
		BigDecimal minAmount = params.path("minAmount").isMissingNode() ? null : params.path("minAmount").decimalValue();
		BigDecimal maxAmount = params.path("maxAmount").isMissingNode() ? null : params.path("maxAmount").decimalValue();

		List<Expense> filtered = raw.stream()
				.filter(e -> categoryFilter == null || (e.getCategory() != null && e.getCategory().getName().equalsIgnoreCase(categoryFilter)))
				.filter(e -> vendorFilter == null || e.getDescription().toLowerCase().contains(vendorFilter.toLowerCase()))
				.filter(e -> minAmount == null || e.getAmount().compareTo(minAmount) >= 0)
				.filter(e -> maxAmount == null || e.getAmount().compareTo(maxAmount) <= 0)
				.limit(50)
				.toList();

		List<Map<String, Object>> items = filtered.stream().map(e -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", e.getId());
			item.put("amount", e.getAmount().setScale(2, RoundingMode.HALF_UP));
			item.put("category", e.getCategory() != null ? e.getCategory().getName() : "Uncategorized");
			item.put("date", e.getDate() != null ? e.getDate().toLocalDate().toString() : "");
			item.put("description", e.getDescription());
			return item;
		}).toList();

		return new ChatResponse("action", "Found " + items.size() + " expense(s).", items);
	}

	private ChatResponse handleGetCategoryTotals(JsonNode params, User user) {
		String month = params.path("month").asText(null);
		List<CategoryReportItem> totals = (month != null && !month.isBlank())
				? expenseService.categoryReportForMonth(user, month)
				: expenseService.categoryReport(user);
		List<Map<String, Object>> items = totals.stream().map(t -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("category", t.category());
			item.put("total", t.total().setScale(2, RoundingMode.HALF_UP));
			return item;
		}).toList();
		String label = (month != null && !month.isBlank()) ? "for " + month : "(all-time)";
		return new ChatResponse("action", "Category totals " + label + ".", items);
	}

	private ChatResponse handleGetMonthlyReport(JsonNode params, User user) {
		String month = params.path("month").asText(YearMonth.now().toString());
		YearMonth ym = YearMonth.parse(month);
		List<CategoryReportItem> breakdown = expenseService.categoryReportForMonth(user, month);
		List<Expense> topExpenses = expenseRepository.findByUserAndDateBetweenOrderByAmountDesc(
				user,
				ym.atDay(1).atStartOfDay(),
				ym.atEndOfMonth().atTime(23, 59, 59),
				org.springframework.data.domain.PageRequest.of(0, 5));

		BigDecimal totalSpent = breakdown.stream()
				.map(CategoryReportItem::total)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.HALF_UP);

		List<Map<String, Object>> categoryBreakdown = breakdown.stream().map(c -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("category", c.category());
			item.put("total", c.total().setScale(2, RoundingMode.HALF_UP));
			return item;
		}).toList();

		List<Map<String, Object>> top = topExpenses.stream().map(e -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", e.getId());
			item.put("amount", e.getAmount().setScale(2, RoundingMode.HALF_UP));
			item.put("category", e.getCategory() != null ? e.getCategory().getName() : "Uncategorized");
			item.put("date", e.getDate() != null ? e.getDate().toLocalDate().toString() : "");
			item.put("description", e.getDescription());
			return item;
		}).toList();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("month", month);
		result.put("totalSpent", totalSpent);
		result.put("categoryBreakdown", categoryBreakdown);
		result.put("topExpenses", top);
		return new ChatResponse("action", "Monthly report for " + month + ": total ₱" + totalSpent.toPlainString() + ".", result);
	}

	private ChatResponse handleMarkAlertRead(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.markRead(id, user);
		return new ChatResponse("action", "Alert #" + id + " marked as read.", null);
	}

	private ChatResponse handleDismissAlert(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.dismiss(id, user);
		return new ChatResponse("action", "Alert #" + id + " dismissed.", null);
	}

	private ChatResponse handleDeleteAlert(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.delete(id, user);
		return new ChatResponse("action", "Alert #" + id + " deleted.", null);
	}

	/**
	 * Wrapped for the same write-then-write reason as {@link #handleCreateBudget}: the category
	 * {@code getOrCreateByName} creates and the profile write that names it are one transaction, so
	 * a failed write cannot strand an empty category (TEN-324).
	 */
	private ChatResponse handleSetDefaultCategory(JsonNode params, User user) {
		return inOneTransaction(() -> setDefaultCategory(params, user));
	}

	private ChatResponse setDefaultCategory(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		Category cat = categoryService.getOrCreateByName(categoryName, user);
		String resolvedName = cat.getName();
		// Same TEN-325 hazard as handleUpdateProfile, more bluntly: this restated five profile
		// fields from the stale principal to change one. It now names only the field it sets.
		userProfileService.patchProfile(user.getEmail(), new UserProfileService.ProfilePatch(
				Optional.empty(),
				Optional.empty(),
				Optional.of(resolvedName),
				Optional.empty()));
		return new ChatResponse("action", "Default category set to \"" + resolvedName + "\".", null);
	}

	private ChatResponse handleSetCategoryIcon(JsonNode params, User user) {
		return inOneTransaction(() -> setCategoryIcon(params, user));
	}

	/**
	 * The {@code @Transactional} that used to sit here was inert — every route in is a same-bean
	 * call. It carried the name forward from the read into the write all the same, so it is wrapped
	 * rather than annotated. Safe to wrap because it catches nothing: no inner exception can mark
	 * the shared transaction rollback-only behind a friendly message.
	 */
	private ChatResponse setCategoryIcon(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		String icon = params.get("icon").asText();
		List<CategoryResponse> all = categoryService.findAll(user);
		CategoryResponse match = all.stream()
				.filter(c -> c.name().equalsIgnoreCase(categoryName))
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryName));
		CategoryResponse result = categoryService.update(match.id(), new CategoryRequest(match.name(), icon), user);
		return new ChatResponse("action", "Icon for \"" + match.name() + "\" updated.", result);
	}

	/**
	 * Deletes several expenses as one transaction, so a failure partway deletes none of them
	 * (TEN-324).
	 *
	 * <p>The {@code @Transactional} that used to sit here was inert twice over: the method was
	 * package-private, which {@code AbstractFallbackTransactionAttributeSource} skips entirely under
	 * {@code allowPublicMethodsOnly()}, and every route in is a same-bean call the proxy never sees.
	 * So a loop of N deletes ran as N transactions — the 25th throwing left the first 24 committed
	 * and reported "Deleted N expense(s)" with no record of which N.
	 *
	 * <p>Nothing is caught inside the callback, deliberately: an id that does not resolve is filtered
	 * out <em>before</em> the delete rather than caught after it, because
	 * {@code ExpenseService.delete} is a live cross-bean {@code @Transactional} whose
	 * {@code ResourceNotFoundException} would mark the shared transaction rollback-only and fail the
	 * commit of the friendly count with {@code UnexpectedRollbackException}. A row someone else
	 * deletes between that check and the delete still aborts the whole batch — that is the atomicity
	 * this buys, not a regression.
	 *
	 * <p>An id repeated in the tool call is a different case and is <em>not</em> left to abort: the
	 * named ids are de-duplicated, because the second delete of the same row would find the first
	 * one's deletion and throw. The per-id catch this replaced absorbed that quietly, so a repeated
	 * id has always meant one deletion and must keep meaning one.
	 */
	private ChatResponse handleDeleteExpenses(JsonNode params, User user) {
		return inOneTransaction(() -> deleteExpenses(params, user));
	}

	private ChatResponse deleteExpenses(JsonNode params, User user) {
		JsonNode idsNode = params.path("ids");
		if (idsNode.isArray() && !idsNode.isEmpty()) {
			Set<Long> named = new LinkedHashSet<>();
			for (JsonNode idNode : idsNode) {
				long id = idNode.asLong();
				if (resolvesForDelete(id, user)) {
					named.add(id);
				}
			}
			return deleteEach(List.copyOf(named), user);
		}

		String fromStr = params.path("from").asText(null);
		String toStr = params.path("to").asText(null);
		String categoryFilter = params.path("category").asText(null);

		// Refuse an unscoped bulk delete (no ids and no filters) — never stage a "delete everything".
		boolean noFilters = (fromStr == null || fromStr.isBlank())
				&& (toStr == null || toStr.isBlank())
				&& (categoryFilter == null || categoryFilter.isBlank());
		if (noFilters) {
			return new ChatResponse("text",
					"Tell me which expenses to delete — by date range, category, or specific items.", null);
		}

		LocalDate from = (fromStr != null && !fromStr.isBlank()) ? LocalDate.parse(fromStr) : null;
		LocalDate to = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr) : null;

		List<Expense> candidates;
		if (from != null && to != null) {
			candidates = expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
					user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
		} else if (from != null) {
			candidates = expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(user, from.atStartOfDay());
		} else if (to != null) {
			candidates = expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(user, to.plusDays(1).atStartOfDay());
		} else {
			candidates = expenseRepository.findAllByUserOrderByDateDesc(user);
		}

		if (categoryFilter != null && !categoryFilter.isBlank()) {
			String cf = categoryFilter;
			candidates = candidates.stream()
					.filter(e -> e.getCategory() != null && e.getCategory().getName().equalsIgnoreCase(cf))
					.toList();
		}

		// The candidates were read in this transaction and are the user's own, so they need no
		// pre-check of their own — the read is the check.
		return deleteEach(candidates.stream().map(Expense::getId).toList(), user);
	}

	/**
	 * The visibility rule {@code ExpenseService.delete} applies, mirrored here so it can be asked
	 * before the call instead of caught after it — see {@link #handleDeleteExpenses} for why
	 * catching it inside the shared transaction is not an option.
	 */
	private boolean resolvesForDelete(long id, User user) {
		return user.isAdmin()
				? expenseRepository.existsById(id)
				: expenseRepository.existsByIdAndUser(id, user);
	}

	private ChatResponse deleteEach(List<Long> ids, User user) {
		for (Long id : ids) {
			expenseService.delete(id, user);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("deleted", ids.size());
		return new ChatResponse("action", "Deleted " + ids.size() + " expense(s).", result);
	}

	private ChatResponse handleRecategorizeExpenses(JsonNode params, User user) {
		return inOneTransaction(() -> recategorizeExpenses(params, user));
	}

	/**
	 * Wrapped for atomicity, not for lost-update detection — see the residual note on
	 * {@link #inOneTransaction}. Under {@code READ COMMITTED} the persistence context still
	 * flushes the snapshot this read produced, so a concurrent edit is still resolved
	 * last-writer-wins. What the wrapper does buy is containment:
	 * {@code categoryService.getOrCreateByName} is a live cross-bean {@code @Transactional} that
	 * would otherwise commit the new category in its own transaction, leaving it orphaned if the
	 * {@code saveAll} that follows fails. The {@code @Transactional} that used to sit here was
	 * inert — every route in is a same-bean call (TEN-324).
	 */
	private ChatResponse recategorizeExpenses(JsonNode params, User user) {
		String fromCategory = params.get("fromCategory").asText();
		String toCategory = params.get("toCategory").asText();
		String fromStr = params.path("from").asText(null);
		String toStr = params.path("to").asText(null);
		LocalDate from = (fromStr != null && !fromStr.isBlank()) ? LocalDate.parse(fromStr) : null;
		LocalDate to = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr) : null;

		List<Expense> candidates;
		if (from != null && to != null) {
			candidates = expenseRepository.findAllByUserAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
					user, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
		} else if (from != null) {
			candidates = expenseRepository.findAllByUserAndDateGreaterThanEqualOrderByDateDesc(user, from.atStartOfDay());
		} else if (to != null) {
			candidates = expenseRepository.findAllByUserAndDateLessThanOrderByDateDesc(user, to.plusDays(1).atStartOfDay());
		} else {
			candidates = expenseRepository.findAllByUserOrderByDateDesc(user);
		}

		String fc = fromCategory;
		List<Expense> matching = candidates.stream()
				.filter(e -> e.getCategory() != null && e.getCategory().getName().equalsIgnoreCase(fc))
				.toList();

		Category targetCat = categoryService.getOrCreateByName(toCategory, user);
		for (Expense e : matching) {
			e.setCategory(targetCat);
		}
		expenseRepository.saveAll(matching);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("updated", matching.size());
		return new ChatResponse("action", "Moved " + matching.size() + " expense(s) from \"" + fromCategory + "\" to \"" + toCategory + "\".", result);
	}
}
