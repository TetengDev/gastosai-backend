package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final BudgetRepository budgetRepository;
    private final ExpenseRepository expenseRepository;

    public AlertService(AlertRepository alertRepository, BudgetRepository budgetRepository,
                        ExpenseRepository expenseRepository) {
        this.alertRepository = alertRepository;
        this.budgetRepository = budgetRepository;
        this.expenseRepository = expenseRepository;
    }

    @Transactional
    public List<AlertResponse> getOrGenerate(User user, String month) {
        String[] parts = month.split("-");
        int year = Integer.parseInt(parts[0]);
        int monthInt = Integer.parseInt(parts[1]);

        generateBudgetAlerts(user, month, year, monthInt);
        generateSpendingSpikeAlert(user, month, year, monthInt);

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
                a.isRead(), a.isDismissed(), a.getCreatedAt()
        );
    }
}
