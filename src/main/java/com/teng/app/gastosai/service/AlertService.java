package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Frequency;
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

    private final AlertRepository alertRepository;
    private final BudgetRepository budgetRepository;
    private final ExpenseRepository expenseRepository;
    private final RecurringExpenseRepository recurringExpenseRepository;

    public AlertService(AlertRepository alertRepository, BudgetRepository budgetRepository,
                        ExpenseRepository expenseRepository,
                        RecurringExpenseRepository recurringExpenseRepository) {
        this.alertRepository = alertRepository;
        this.budgetRepository = budgetRepository;
        this.expenseRepository = expenseRepository;
        this.recurringExpenseRepository = recurringExpenseRepository;
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
            BigDecimal limit = budget.getAmountLimit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal spent = spentByCategory
                    .getOrDefault(budget.getCategory().getId(), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);

            if (limit.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal percentUsed = spent.divide(limit, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            String categoryName = budget.getCategory().getName();

            if (percentUsed.compareTo(BigDecimal.valueOf(100)) >= 0) {
                upsertAlert(user, AlertType.BUDGET_EXCEEDED, AlertSeverity.CRITICAL, month, categoryName,
                        String.format("Budget exceeded for %s: spent ₱%.2f of ₱%.2f budget.",
                                categoryName, spent, limit));
            } else if (percentUsed.compareTo(BigDecimal.valueOf(80)) >= 0) {
                upsertAlert(user, AlertType.BUDGET_WARNING, AlertSeverity.WARNING, month, categoryName,
                        String.format("Approaching budget limit for %s: spent ₱%.2f of ₱%.2f (%.0f%%).",
                                categoryName, spent, limit, percentUsed));
            }
        }
    }

    private void generateSpendingSpikeAlert(User user, String month, int year, int monthInt) {
        YearMonth prevYM = YearMonth.of(year, monthInt).minusMonths(1);
        BigDecimal currentTotal = expenseRepository.sumForMonth(user, year, monthInt);
        BigDecimal prevTotal = expenseRepository.sumForMonth(user, prevYM.getYear(), prevYM.getMonthValue());

        if (prevTotal == null || prevTotal.compareTo(BigDecimal.ZERO) == 0) return;
        if (currentTotal == null) return;

        BigDecimal threshold = prevTotal.multiply(BigDecimal.valueOf(1.5));
        if (currentTotal.compareTo(threshold) > 0) {
            upsertAlert(user, AlertType.SPENDING_SPIKE, AlertSeverity.WARNING, month, "",
                    String.format("Spending spike detected: ₱%.2f this month vs ₱%.2f last month (+%.0f%%).",
                            currentTotal, prevTotal,
                            currentTotal.subtract(prevTotal)
                                    .divide(prevTotal, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))));
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
