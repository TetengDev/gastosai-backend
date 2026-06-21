package com.teng.app.gastosai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.ai.ChatTool;
import com.teng.app.gastosai.ai.ChatToolCall;
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
import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.UpcomingBillResponse;
import com.teng.app.gastosai.dto.UserProfileRequest;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.Expense;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
	private final ObjectMapper objectMapper;
	private final AiQuotaService aiQuotaService;
	private final AiUsageService aiUsageService;
	private final AiRedactionService aiRedactionService;
	private final AiManagedProperties aiManagedProperties;
	private final AiProviderProperties aiProviderProperties;
	private final OpenAiProperties openAiProperties;
	private final ClaudeProperties claudeProperties;
	private final ConversationService conversationService;

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

		ChatResponse response = dispatch(effectiveMessage, mode, user);

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
		aiQuotaService.assertWithinQuota(user, AiFeature.CHAT_CRUD_ASSISTANT);
		int max = aiManagedProperties.getMaxPromptChars();
		String safeMessage = aiRedactionService.redact(message);
		if (safeMessage != null && safeMessage.length() > max) {
			safeMessage = safeMessage.substring(0, max);
		}
		final String finalMessage = safeMessage;
		try {
			ChatToolCall call = sqlGenerator.classifyIntent(finalMessage);
			ChatTool tool = ChatTool.fromKey(call.toolName());

			if (tool == ChatTool.TEXT) {
				// best-effort: provider usage not surfaced here yet (TODO wire tokens)
				aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
						resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
						null, null, AiUsageStatus.SUCCESS, null);
				return new ChatResponse("text", call.paramsJson(), null);
			}

			JsonNode params = objectMapper.readTree(call.paramsJson());

			if ((tool.isCreate() || tool.isDestructive()) && !isRunMode(mode)) {
				Map<String, Object> previewData = new LinkedHashMap<>();
				previewData.put("toolName", tool.key());
				previewData.put("params", objectMapper.convertValue(params, Map.class));
				aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
						resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
						null, null, AiUsageStatus.SUCCESS, null);
				return new ChatResponse("preview", buildPreviewMessage(tool, params), previewData);
			}

			ChatResponse response = switch (tool) {
				case CREATE_EXPENSE -> handleCreateExpense(params, user, MODE_FORCE.equals(mode));
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
				default -> new ChatResponse("text", call.paramsJson(), null);
			};
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					null, null, AiUsageStatus.SUCCESS, null);
			return response;
		}
		catch (ResourceNotFoundException e) {
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					null, null, AiUsageStatus.FAILED, "ResourceNotFoundException");
			return new ChatResponse("text", "I couldn't find that item.", null);
		}
		catch (Exception e) {
			log.warn("chat_action_failed", e);
			aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
					resolveModel(), AiFeature.CHAT_CRUD_ASSISTANT,
					null, null, AiUsageStatus.FAILED, e.getClass().getSimpleName());
			return new ChatResponse("text", "Something went wrong while handling that. Please rephrase and try again.", null);
		}
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

	@Transactional(readOnly = true)
	protected java.util.Optional<Expense> findRecentDuplicate(User user, BigDecimal amount, String description) {
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

		ExpenseRequest req = new ExpenseRequest(amount, category, date, description, null, null, null, null);
		Object result = expenseService.create(req, user);
		return new ChatResponse("action", "Expense created: ₱" + amount.toPlainString() + " — " + description, result);
	}

	private ChatResponse handleUpdateExpense(JsonNode params, User user) {
		long id = params.get("id").asLong();
		BigDecimal amount = params.get("amount").decimalValue();
		String category = params.path("category").asText("Uncategorized");
		String description = params.get("description").asText();
		String dateStr = params.path("date").asText(null);
		LocalDateTime date = (dateStr != null && !dateStr.isBlank())
				? LocalDate.parse(dateStr).atStartOfDay()
				: null;
		ExpenseRequest req = new ExpenseRequest(amount, category, date, description, null, null, null, null);
		Object result = expenseService.update(id, req, user);
		return new ChatResponse("action", "Expense #" + id + " updated.", result);
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

	private ChatResponse handleCreateBudget(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		String month = params.path("month").asText(YearMonth.now().toString());
		BigDecimal amountLimit = params.get("amountLimit").decimalValue();
		try {
			Category cat = categoryService.getOrCreateByName(categoryName, user);
			BudgetRequest req = new BudgetRequest(cat.getId(), month, amountLimit, null, null);
			Object result = budgetService.create(req, user);
			return new ChatResponse("action", "Budget created for " + categoryName + " (₱" + amountLimit.toPlainString() + ").", result);
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

	private ChatResponse handleUpdateBudget(JsonNode params, User user) {
		long id = params.path("id").asLong(0);
		BigDecimal amountLimit = params.get("amountLimit").decimalValue();
		String month = params.path("month").asText(YearMonth.now().toString());
		String categoryName = params.path("categoryName").asText(null);

		if (id > 0) {
			Budget budget = budgetRepository.findByIdAndUser(id, user)
					.orElseThrow(() -> new ResourceNotFoundException("Budget not found: " + id));
			BudgetRequest req = new BudgetRequest(budget.getCategory().getId(), budget.getMonth(), amountLimit, null, null);
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
			BudgetRequest req = new BudgetRequest(match.getCategory().getId(), month, amountLimit, null, null);
			Object result = budgetService.update(match.getId(), req, user);
			return new ChatResponse("action", "Budget for " + categoryName + " updated to ₱" + amountLimit.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".", result);
		}

		return new ChatResponse("text", "Please specify a budget ID or category name to update.", null);
	}

	private ChatResponse handleUpdateGoal(JsonNode params, User user) {
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

	private ChatResponse handleUpdateRecurring(JsonNode params, User user) {
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

	private ChatResponse handleRenameCategory(JsonNode params, User user) {
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
		try {
			CategoryResponse result = categoryService.update(match.id(), new CategoryRequest(newName, match.icon()), user);
			return new ChatResponse("action", "Category \"" + currentName + "\" renamed to \"" + newName + "\".", result);
		} catch (IllegalArgumentException e) {
			return new ChatResponse("text", e.getMessage(), null);
		}
	}

	private ChatResponse handleDeleteCategory(JsonNode params, User user) {
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
		try {
			categoryService.delete(match.id(), user);
			return new ChatResponse("action", "Category \"" + name + "\" deleted. Affected expenses moved to Uncategorized.", null);
		} catch (IllegalArgumentException e) {
			return new ChatResponse("text", e.getMessage(), null);
		}
	}

	private ChatResponse handleListCategories(User user) {
		List<CategoryResponse> categories = categoryService.findAll(user);
		return new ChatResponse("action", "You have " + categories.size() + " categories.", categories);
	}

	private ChatResponse handleUpdateProfile(JsonNode params, User user) {
		String name = params.path("name").asText(null);
		String nickname = params.path("nickname").asText(null);
		String avatar = params.path("avatar").asText(null);
		String defaultCategory = params.path("defaultCategory").asText(null);

		String resolvedName = (name != null && !name.isBlank()) ? name : user.getName();
		String resolvedNickname = (nickname != null) ? nickname : user.getNickname();
		String resolvedAvatar = (avatar != null && !avatar.isBlank()) ? avatar : user.getAvatar();
		String resolvedDefaultCategory = (defaultCategory != null && !defaultCategory.isBlank()) ? defaultCategory : user.getDefaultCategoryName();
		String resolvedAvatarColor = user.getAvatarColor();

		UserProfileRequest req = new UserProfileRequest(resolvedName, resolvedNickname, user.getEmail(), resolvedAvatarColor, resolvedDefaultCategory, resolvedAvatar);
		Object result = userProfileService.updateProfile(user.getEmail(), req);
		return new ChatResponse("action", "Profile updated.", result);
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

	@Transactional(readOnly = true)
	ChatResponse handleListGoals(User user) {
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

	@Transactional(readOnly = true)
	ChatResponse handleListBudgets(JsonNode params, User user) {
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

	@Transactional(readOnly = true)
	ChatResponse handleListRecurring(JsonNode params, User user) {
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

	@Transactional
	ChatResponse handleListAlerts(JsonNode params, User user) {
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

	@Transactional(readOnly = true)
	ChatResponse handleSearchExpenses(JsonNode params, User user) {
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

	@Transactional(readOnly = true)
	ChatResponse handleGetCategoryTotals(JsonNode params, User user) {
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

	@Transactional(readOnly = true)
	ChatResponse handleGetMonthlyReport(JsonNode params, User user) {
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

	@Transactional
	ChatResponse handleMarkAlertRead(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.markRead(id, user);
		return new ChatResponse("action", "Alert #" + id + " marked as read.", null);
	}

	@Transactional
	ChatResponse handleDismissAlert(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.dismiss(id, user);
		return new ChatResponse("action", "Alert #" + id + " dismissed.", null);
	}

	@Transactional
	ChatResponse handleDeleteAlert(JsonNode params, User user) {
		long id = params.get("id").asLong();
		alertService.delete(id, user);
		return new ChatResponse("action", "Alert #" + id + " deleted.", null);
	}

	@Transactional
	ChatResponse handleSetDefaultCategory(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		Category cat = categoryService.getOrCreateByName(categoryName, user);
		String resolvedName = cat.getName();
		UserProfileRequest req = new UserProfileRequest(
				user.getName(),
				user.getNickname(),
				user.getEmail(),
				user.getAvatarColor(),
				resolvedName,
				user.getAvatar());
		userProfileService.updateProfile(user.getEmail(), req);
		return new ChatResponse("action", "Default category set to \"" + resolvedName + "\".", null);
	}

	@Transactional
	ChatResponse handleSetCategoryIcon(JsonNode params, User user) {
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

	@Transactional
	ChatResponse handleDeleteExpenses(JsonNode params, User user) {
		JsonNode idsNode = params.path("ids");
		if (idsNode.isArray() && !idsNode.isEmpty()) {
			int deleted = 0;
			for (JsonNode idNode : idsNode) {
				long id = idNode.asLong();
				try {
					expenseService.delete(id, user);
					deleted++;
				} catch (ResourceNotFoundException ignored) {
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("deleted", deleted);
			return new ChatResponse("action", "Deleted " + deleted + " expense(s).", result);
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

		int deleted = 0;
		List<Long> ids = candidates.stream().map(Expense::getId).toList();
		for (Long id : ids) {
			try {
				expenseService.delete(id, user);
				deleted++;
			} catch (ResourceNotFoundException ignored) {
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("deleted", deleted);
		return new ChatResponse("action", "Deleted " + deleted + " expense(s).", result);
	}

	@Transactional
	ChatResponse handleRecategorizeExpenses(JsonNode params, User user) {
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
