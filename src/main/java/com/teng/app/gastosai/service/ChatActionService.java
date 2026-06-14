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
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class ChatActionService {

	private final SqlGenerator sqlGenerator;
	private final ExpenseService expenseService;
	private final BudgetService budgetService;
	private final SavingsGoalService savingsGoalService;
	private final RecurringExpenseService recurringExpenseService;
	private final CategoryService categoryService;
	private final ObjectMapper objectMapper;

	public ChatResponse dispatch(String message, String mode, User user) {
		try {
			ChatToolCall call = sqlGenerator.classifyIntent(message);

			if ("text".equals(call.toolName())) {
				return new ChatResponse("text", call.paramsJson(), null);
			}

			JsonNode params = objectMapper.readTree(call.paramsJson());

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
		long id = params.get("id").asLong();
		expenseService.delete(id, user);
		return new ChatResponse("action", "Expense #" + id + " deleted.", null);
	}

	private ChatResponse handleCreateBudget(JsonNode params, User user) {
		String categoryName = params.get("categoryName").asText();
		String month = params.path("month").asText(YearMonth.now().toString());
		BigDecimal amountLimit = params.get("amountLimit").decimalValue();
		Category cat = categoryService.getOrCreateByName(categoryName);
		BudgetRequest req = new BudgetRequest(cat.getId(), month, amountLimit, null, null);
		Object result = budgetService.create(req, user);
		return new ChatResponse("action", "Budget created for " + categoryName + " (₱" + amountLimit.toPlainString() + ").", result);
	}

	private ChatResponse handleDeleteBudget(JsonNode params, User user) {
		long id = params.get("id").asLong();
		budgetService.delete(id, user);
		return new ChatResponse("action", "Budget #" + id + " deleted.", null);
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
		long id = params.get("id").asLong();
		savingsGoalService.delete(id, user);
		return new ChatResponse("action", "Goal #" + id + " deleted.", null);
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

	private ChatResponse handleDeleteRecurring(JsonNode params, User user) {
		long id = params.get("id").asLong();
		recurringExpenseService.delete(id, user);
		return new ChatResponse("action", "Recurring expense #" + id + " deleted.", null);
	}
}
