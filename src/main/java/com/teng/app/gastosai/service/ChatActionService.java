package com.teng.app.gastosai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.ChatToolCall;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.BudgetRequest;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatActionService {

	private final SqlGenerator sqlGenerator;
	private final ExpenseService expenseService;
	private final BudgetService budgetService;
	private final SavingsGoalService savingsGoalService;
	private final RecurringExpenseService recurringExpenseService;
	private final CategoryService categoryService;
	private final ExpenseRepository expenseRepository;
	private final RecurringExpenseRepository recurringExpenseRepository;
	private final BudgetRepository budgetRepository;
	private final SavingsGoalRepository savingsGoalRepository;
	private final ObjectMapper objectMapper;

	public ChatResponse dispatch(String message, String mode, User user) {
		try {
			ChatToolCall call = sqlGenerator.classifyIntent(message);

			if ("text".equals(call.toolName())) {
				return new ChatResponse("text", call.paramsJson(), null);
			}

			JsonNode params = objectMapper.readTree(call.paramsJson());

			if (call.toolName().startsWith("create_") && !"execute".equals(mode)) {
				Map<String, Object> previewData = new LinkedHashMap<>();
				previewData.put("toolName", call.toolName());
				previewData.put("params", objectMapper.convertValue(params, Map.class));
				return new ChatResponse("preview", buildPreviewMessage(call.toolName(), params), previewData);
			}

			return switch (call.toolName()) {
				case "create_expense" -> handleCreateExpense(params, user);
				case "update_expense" -> handleUpdateExpense(params, user);
				case "delete_expense" -> handleDeleteExpense(params, user);
				case "create_budget" -> handleCreateBudget(params, user);
				case "delete_budget" -> handleDeleteBudget(params, user);
				case "create_goal" -> handleCreateGoal(params, user);
				case "delete_goal" -> handleDeleteGoal(params, user);
				case "create_recurring" -> handleCreateRecurring(params, user);
				case "delete_recurring" -> handleDeleteRecurring(params, user);
				default -> new ChatResponse("text", call.paramsJson(), null);
			};
		}
		catch (ResourceNotFoundException e) {
			return new ChatResponse("text", "I couldn't find that item.", null);
		}
		catch (Exception e) {
			return new ChatResponse("text", "Something went wrong: " + e.getMessage(), null);
		}
	}

	private String buildPreviewMessage(String toolName, JsonNode params) {
		return switch (toolName) {
			case "create_budget" -> "Create budget for " + params.path("categoryName").asText("category") + " — ₱" + params.path("amountLimit").asText("0") + "?";
			case "create_goal" -> "Create goal \"" + params.path("name").asText() + "\" — ₱" + params.path("targetAmount").asText("0") + "?";
			case "create_recurring" -> "Create recurring \"" + params.path("name").asText() + "\" — ₱" + params.path("amount").asText("0") + "/" + params.path("frequency").asText("monthly").toLowerCase() + "?";
			case "create_expense" -> "Create expense ₱" + params.path("amount").asText("0") + " for " + params.path("description").asText() + "?";
			default -> "Confirm action?";
		};
	}

	private ChatResponse handleCreateExpense(JsonNode params, User user) {
		BigDecimal amount = params.get("amount").decimalValue();
		String category = params.path("category").asText("Uncategorized");
		String description = params.get("description").asText();
		String dateStr = params.path("date").asText(null);
		LocalDateTime date = (dateStr != null && !dateStr.isBlank())
				? LocalDate.parse(dateStr).atStartOfDay()
				: null;
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
			Category cat = categoryService.getOrCreateByName(categoryName);
			BudgetRequest req = new BudgetRequest(cat.getId(), month, amountLimit, null, null);
			Object result = budgetService.create(req, user);
			return new ChatResponse("action", "Budget created for " + categoryName + " (₱" + amountLimit.toPlainString() + ").", result);
		} catch (ResponseStatusException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				Category cat = categoryService.getOrCreateByName(categoryName);
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
					previewData.put("toolName", "update_budget");
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
		Object result = savingsGoalService.create(req, user);
		return new ChatResponse("action", "Goal created: " + name + " (₱" + targetAmount.toPlainString() + ").", result);
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
		Object result = recurringExpenseService.create(req, user);
		return new ChatResponse("action", "Recurring expense created: " + name + " (₱" + amount.toPlainString() + ").", result);
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
}
