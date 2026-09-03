package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetResponse;
import com.teng.app.gastosai.dto.ChatPreviewData;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.dto.RecurringExpenseResponse;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restates {@link ChatResponse#result()} with its money in integer centavos.
 *
 * <p>This is the one part of the v2 surface that cannot be a DTO-to-DTO conversion.
 * {@code ChatActionService} builds most chat payloads as {@code LinkedHashMap}s and the field is
 * typed {@code Object}, so there is no v1 record to hand a {@code from} method — the payload
 * arrives as a map, a list, or (on a turn that echoes a written row) a resource DTO. Converting it
 * here rather than typing the service keeps the change inside {@code /api/v2}: v1 keeps serving the
 * decimals its clients are pinned to, and there is still one handler behind both paths.
 *
 * <p>Two rules, and both are load-bearing:
 *
 * <ul>
 *   <li><strong>Only the keys in {@link #MONEY_KEYS} are converted</strong>, and only where the
 *       value is a number. Each one is a money property of a published {@code *V2} chat schema:
 *       {@code amount} ({@link ExpenseChatItemV2}, {@link RecurringChatItemV2},
 *       {@link UpcomingBillChatItemV2}, {@link ExpenseDisambiguateItemV2}), {@code total}
 *       ({@link CategoryTotalChatItemV2}), {@code totalBudgeted} / {@code totalSpent} /
 *       {@code safeToSpend} ({@link BudgetSummaryChatResultV2},
 *       {@link MonthlyReportChatResultV2}), {@code budgeted} / {@code spent} / {@code remaining}
 *       ({@link BudgetChatItemV2}), and {@code targetAmount} / {@code savedAmount}
 *       ({@link GoalChatItemV2}). A key not on that list is copied through untouched, so an
 *       unrecognised payload degrades to the v1 shape rather than to a mangled one.
 *   <li><strong>A {@link ChatPreviewData} payload is passed through whole.</strong> Its
 *       {@code params} are the arguments the client echoes back to {@code POST /ai/chat/confirm} —
 *       which has no v2 twin, so the confirmation lands on the v1 handler and is re-read as
 *       decimals. Converting them would make every confirmed amount a hundred times too large.
 * </ul>
 */
final class ChatResultV2 {

	/**
	 * The map keys that carry money on a chat payload. Deliberately a closed list rather than a
	 * name pattern: {@code currentAmount} and {@code amountLimit} also look like money and appear
	 * only inside preview params, which must not be converted.
	 */
	private static final Set<String> MONEY_KEYS = Set.of(
			"amount", "total", "totalBudgeted", "totalSpent", "safeToSpend",
			"budgeted", "spent", "remaining", "targetAmount", "savedAmount");

	private ChatResultV2() {
	}

	/** The payload with every money field restated in centavos. */
	static Object from(Object result) {
		return switch (result) {
			case null -> null;
			case ChatPreviewData preview -> preview;
			case ExpenseResponse expense -> ExpenseResponseV2.from(expense);
			case BudgetResponse budget -> BudgetResponseV2.from(budget);
			case GoalResponse goal -> GoalResponseV2.from(goal);
			case RecurringExpenseResponse recurring -> RecurringExpenseResponseV2.from(recurring);
			case Map<?, ?> map -> fromMap(map);
			case List<?> list -> list.stream().map(ChatResultV2::from).toList();
			default -> result;
		};
	}

	private static Object fromMap(Map<?, ?> map) {
		// The preview and duplicate-confirmation turns build their payload as a map rather than a
		// ChatPreviewData, so the pass-through above does not catch them. `toolName` plus `params`
		// is what identifies one on the wire, and it is the same pair the client sends back.
		if (map.containsKey("toolName") && map.containsKey("params")) {
			return map;
		}

		Map<String, Object> converted = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			String name = String.valueOf(key);
			converted.put(name, MONEY_KEYS.contains(name) ? toCentavos(value) : from(value));
		});
		return converted;
	}

	/**
	 * A money value as centavos, or the value unchanged when it is not a number — a null amount
	 * stays null, and anything else is left alone rather than guessed at.
	 */
	private static Object toCentavos(Object value) {
		return switch (value) {
			case null -> null;
			case BigDecimal decimal -> Money.toCentavos(decimal);
			case Number number -> Money.toCentavos(new BigDecimal(number.toString()));
			default -> value;
		};
	}
}
