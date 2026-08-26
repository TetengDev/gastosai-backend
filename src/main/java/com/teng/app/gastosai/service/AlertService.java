package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.ViewAsContext;
import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AlertService {

    private static final int RECURRING_DUE_DAYS_AHEAD = 3;

    /** How many months before the flagged one the "unusual against what?" baseline looks at. */
    private static final int BASELINE_MONTHS = 3;

    /**
     * How many of those months must actually carry spending. Below this the baseline is a guess
     * dressed up as a comparison, so the anomaly is flagged with no explanation at all.
     */
    private static final int MIN_BASELINE_MONTHS = 3;

    private final AlertRepository alertRepository;
    private final BudgetRepository budgetRepository;
    private final ExpenseRepository expenseRepository;
    private final RecurringExpenseRepository recurringExpenseRepository;
    private final EntitlementService entitlementService;

    public AlertService(AlertRepository alertRepository, BudgetRepository budgetRepository,
                        ExpenseRepository expenseRepository,
                        RecurringExpenseRepository recurringExpenseRepository,
                        EntitlementService entitlementService) {
        this.alertRepository = alertRepository;
        this.budgetRepository = budgetRepository;
        this.expenseRepository = expenseRepository;
        this.recurringExpenseRepository = recurringExpenseRepository;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public List<AlertResponse> getOrGenerate(User user, String month) {
        String[] parts = month.split("-");
        int year = Integer.parseInt(parts[0]);
        int monthInt = Integer.parseInt(parts[1]);

        generateBudgetAlerts(user, month, year, monthInt);
        generateSpendingSpikeAlert(user, month, year, monthInt);
        generateRecurringDueAlerts(user, month);

        return alertRepository
                .findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(user, month)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void generateBudgetAlerts(User user, String month, int year, int monthInt) {
        List<Budget> budgets = budgetRepository.findAllByUserAndMonth(user, month);

        if (budgets.isEmpty()) return;

        List<Object[]> spentRows = expenseRepository.sumByCategoryAndMonth(user, year, monthInt);
        Map<Long, BigDecimal> spentByCategory = spentRows.stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> new BigDecimal(row[1].toString())
                ));

        for (Budget budget : budgets) {
            String categoryName = budget.getCategory().getName();

            BigDecimal limit = budget.getAmountLimit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal spent = spentByCategory
                    .getOrDefault(budget.getCategory().getId(), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);

            if (limit.compareTo(BigDecimal.ZERO) == 0) {
                // No limit to breach, so neither condition can hold any more.
                retireAlert(user, AlertType.BUDGET_EXCEEDED, month, categoryName);
                retireAlert(user, AlertType.BUDGET_WARNING, month, categoryName);
                continue;
            }

            BigDecimal percentUsed = spent.divide(limit, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (percentUsed.compareTo(BigDecimal.valueOf(100)) >= 0) {
                upsertAlert(user, AlertType.BUDGET_EXCEEDED, AlertSeverity.CRITICAL, month, categoryName,
                        String.format("Budget exceeded for %s: spent ₱%.2f of ₱%.2f budget.",
                                categoryName, spent, limit));
                retireAlert(user, AlertType.BUDGET_WARNING, month, categoryName);
            } else if (percentUsed.compareTo(BigDecimal.valueOf(80)) >= 0) {
                upsertAlert(user, AlertType.BUDGET_WARNING, AlertSeverity.WARNING, month, categoryName,
                        String.format("Approaching budget limit for %s: spent ₱%.2f of ₱%.2f (%.0f%%).",
                                categoryName, spent, limit, percentUsed));
                retireAlert(user, AlertType.BUDGET_EXCEEDED, month, categoryName);
            } else {
                retireAlert(user, AlertType.BUDGET_EXCEEDED, month, categoryName);
                retireAlert(user, AlertType.BUDGET_WARNING, month, categoryName);
            }
        }
    }

    private void generateSpendingSpikeAlert(User user, String month, int year, int monthInt) {
        YearMonth prevYM = YearMonth.of(year, monthInt).minusMonths(1);
        BigDecimal currentTotal = expenseRepository.sumForMonth(user, year, monthInt);
        BigDecimal prevTotal = expenseRepository.sumForMonth(user, prevYM.getYear(), prevYM.getMonthValue());

        if (prevTotal == null || prevTotal.compareTo(BigDecimal.ZERO) == 0 || currentTotal == null) {
            retireAlert(user, AlertType.SPENDING_SPIKE, month, "");
            return;
        }

        BigDecimal threshold = prevTotal.multiply(BigDecimal.valueOf(1.5));
        if (currentTotal.compareTo(threshold) <= 0) {
            retireAlert(user, AlertType.SPENDING_SPIKE, month, "");
            return;
        }

        String message = String.format("Spending spike detected: ₱%.2f this month vs ₱%.2f last month (+%.0f%%).",
                currentTotal, prevTotal,
                currentTotal.subtract(prevTotal)
                        .divide(prevTotal, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)));

        String explanation = explainSpike(user, year, monthInt, currentTotal);
        if (!explanation.isEmpty()) {
            message = message + " " + explanation;
        }

        upsertAlert(user, AlertType.SPENDING_SPIKE, AlertSeverity.WARNING, month, "", message);
    }

    /**
     * One sentence naming the comparison the month is unusual against, so a user can tell a real
     * problem from a normally irregular month. Returns an empty string — the alert is still raised,
     * just unexplained — when the user's plan lacks {@link FeatureKey#ANOMALY_DETECTION}, or when
     * fewer than {@link #MIN_BASELINE_MONTHS} of the preceding months carry any spending to compare
     * against.
     */
    private String explainSpike(User user, int year, int monthInt, BigDecimal currentTotal) {
        if (!mayExplain(user)) {
            return "";
        }

        YearMonth flagged = YearMonth.of(year, monthInt);
        BigDecimal baselineSum = BigDecimal.ZERO;
        int monthsWithSpending = 0;
        for (int back = 1; back <= BASELINE_MONTHS; back++) {
            YearMonth previous = flagged.minusMonths(back);
            BigDecimal total = expenseRepository.sumForMonth(user, previous.getYear(), previous.getMonthValue());
            if (total == null || total.signum() <= 0) continue;
            baselineSum = baselineSum.add(total);
            monthsWithSpending++;
        }

        if (monthsWithSpending < MIN_BASELINE_MONTHS) return "";

        BigDecimal average = baselineSum.divide(BigDecimal.valueOf(monthsWithSpending), 2, RoundingMode.HALF_UP);
        if (average.signum() <= 0) return "";

        BigDecimal timesAverage = currentTotal.divide(average, 1, RoundingMode.HALF_UP);
        return String.format(
                "This is unusual against your previous %d months, which averaged ₱%.2f — this month is %.1f× that.",
                monthsWithSpending, average, timesAverage);
    }

    /**
     * Whether this user's plan grants the explanation — asked of their real plan, never of an admin
     * "View As" simulation.
     *
     * <p>{@link ViewAsContext} is a read-only preview: an admin previewing FREE is meant to see the
     * app as a free user, not to change anything. But the explanation is not rendered per request,
     * it is persisted into {@code Alert.message} by the upsert below, so honouring the simulated
     * plan here would let a preview rewrite the admin's own stored alert — and the next preview at
     * a different tier would rewrite it again. The simulation is suspended for the length of the
     * check and restored immediately, so everything else in the request still sees it.
     */
    private boolean mayExplain(User user) {
        PlanKey simulated = ViewAsContext.plan();
        if (simulated == null) {
            return entitlementService.canAccessFeature(user, FeatureKey.ANOMALY_DETECTION);
        }
        Boolean simulatedAi = ViewAsContext.aiEnabled();
        ViewAsContext.clear();
        try {
            return entitlementService.canAccessFeature(user, FeatureKey.ANOMALY_DETECTION);
        } finally {
            ViewAsContext.set(simulated, simulatedAi);
        }
    }

    private void generateRecurringDueAlerts(User user, String month) {
        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(RECURRING_DUE_DAYS_AHEAD);

        List<RecurringExpense> expenses = recurringExpenseRepository.findAllByUser(user);
        for (RecurringExpense expense : expenses) {
            if (!expense.isActive()) continue;

            LocalDate dueDate = null;

            if (expense.getFrequency() == Frequency.MONTHLY && expense.getDayOfMonth() != null) {
                try {
                    dueDate = LocalDate.of(today.getYear(), today.getMonthValue(), expense.getDayOfMonth());
                } catch (Exception e) {
                    continue;
                }
            } else if (expense.getFrequency() == Frequency.WEEKLY && expense.getDayOfWeek() != null) {
                dueDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(expense.getDayOfWeek())));
            }

            if (dueDate == null) continue;
            if (dueDate.isBefore(today) || dueDate.isAfter(windowEnd)) continue;

            upsertRecurringDueAlert(user, expense, dueDate, month);
        }
    }

    private void upsertRecurringDueAlert(User user, RecurringExpense expense, LocalDate dueDate, String month) {
        alertRepository.findByUserAndTypeAndMonthAndRecurringExpenseId(
                        user, AlertType.RECURRING_DUE, month, expense.getId())
                .ifPresentOrElse(
                        existing -> {
                            existing.setMessage(buildRecurringMessage(expense, dueDate));
                            alertRepository.save(existing);
                        },
                        () -> {
                            String categoryName = expense.getCategory() != null
                                    ? expense.getCategory().getName()
                                    : "Uncategorized";
                            alertRepository.save(Alert.builder()
                                    .user(user)
                                    .type(AlertType.RECURRING_DUE)
                                    .severity(AlertSeverity.INFO)
                                    .month(month)
                                    .categoryName(categoryName)
                                    .message(buildRecurringMessage(expense, dueDate))
                                    .recurringExpenseId(expense.getId())
                                    .build());
                        });
    }

    private String buildRecurringMessage(RecurringExpense expense, LocalDate dueDate) {
        return expense.getName() + " is due on " + dueDate
                + " (₱" + expense.getAmount().setScale(2, RoundingMode.HALF_UP) + ")";
    }

    /**
     * Deletes an alert whose condition no longer holds, so it stops being shown for a month it is
     * no longer true of. An alert is derived state — it is recomputed on every read of the month —
     * so a row that no longer describes the data is not worth keeping; there is no "resolved" state
     * on {@link Alert} and adding one would only put stale text somewhere else.
     *
     * <p>A <em>dismissed</em> alert is deliberately left alone. It is already out of the user's
     * list, so retiring it buys nothing, and deleting it would throw away the user's decision: if
     * the condition later starts holding again, the upsert refreshes that same row and it stays
     * silenced, instead of resurfacing an alert the user had silenced.
     */
    private void retireAlert(User user, AlertType type, String month, String categoryName) {
        alertRepository.findByUserAndTypeAndMonthAndCategoryName(user, type, month, categoryName)
                .filter(existing -> !existing.isDismissed())
                .ifPresent(alertRepository::delete);
    }

    private void upsertAlert(User user, AlertType type, AlertSeverity severity,
                             String month, String categoryName, String message) {
        alertRepository.findByUserAndTypeAndMonthAndCategoryName(user, type, month, categoryName)
                .ifPresentOrElse(
                        existing -> {
                            existing.setMessage(message);
                            existing.setSeverity(severity);
                            alertRepository.save(existing);
                        },
                        () -> alertRepository.save(Alert.builder()
                                .user(user).type(type).severity(severity)
                                .month(month).categoryName(categoryName).message(message)
                                .build())
                );
    }

    @Transactional
    public AlertResponse markRead(Long id, User user) {
        Alert alert = findAlert(id, user);
        alert.setRead(true);
        return toResponse(alertRepository.save(alert));
    }

    @Transactional
    public AlertResponse dismiss(Long id, User user) {
        Alert alert = findAlert(id, user);
        alert.setDismissed(true);
        return toResponse(alertRepository.save(alert));
    }

    @Transactional
    public void delete(Long id, User user) {
        Alert alert = findAlert(id, user);
        alertRepository.delete(alert);
    }

    private Alert findAlert(Long id, User user) {
        return alertRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found: " + id));
    }

    private AlertResponse toResponse(Alert a) {
        return new AlertResponse(
                a.getId(), a.getType(), a.getSeverity(),
                a.getMonth(), a.getCategoryName(), a.getMessage(),
                a.isRead(), a.isDismissed(), a.getCreatedAt(),
                a.getRecurringExpenseId()
        );
    }
}
