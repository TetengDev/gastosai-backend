package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AlertProperties;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically checks cost/abuse/error thresholds and fires a Telegram alert when breached.
 * Each condition is de-duplicated to a time window (day or hour) so a sustained breach alerts
 * once, not every tick. Runs only while the instance is awake — Render free-tier sleep means
 * this is NOT a downtime detector (use an external uptime pinger for that; see docs/observability.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertScheduler {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    private final AlertProperties props;
    private final TelegramAlertService telegram;
    private final AiUsageRepository aiUsageRepository;
    private final AppEventRepository appEventRepository;
    private final AiManagedProperties managedProps;

    /** condition key -> last-fired window marker (date or hour), so a breach alerts once per window. */
    private final ConcurrentHashMap<String, String> lastFired = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${gastos.alerts.interval-ms:900000}")
    public void runChecks() {
        if (!props.isActive()) {
            return;
        }
        checkDailyCost();
        checkGlobalCap();
        checkErrorSpike();
    }

    private void checkDailyCost() {
        BigDecimal spend = aiUsageRepository.sumEstimatedCostSince(LocalDate.now().atStartOfDay());
        if (spend.compareTo(props.getDailyCostUsd()) > 0) {
            fireOnce("daily-cost", today(), () ->
                    "GastosAI alert: today's estimated AI spend is $" + spend.toPlainString()
                            + " (threshold $" + props.getDailyCostUsd().toPlainString() + ").");
        }
    }

    private void checkGlobalCap() {
        int max = managedProps.getGlobalDailyMax();
        if (max <= 0) {
            return;
        }
        long used = aiUsageRepository.countByStatusAndCreatedAtAfter(AiUsageStatus.SUCCESS,
                LocalDate.now().atStartOfDay());
        long threshold = Math.round(max * props.getGlobalCapWarnFraction());
        if (used >= threshold) {
            fireOnce("global-cap", today(), () ->
                    "GastosAI alert: global daily AI usage at " + used + "/" + max
                            + " requests (" + Math.round(props.getGlobalCapWarnFraction() * 100) + "% warn threshold).");
        }
    }

    private void checkErrorSpike() {
        LocalDateTime now = LocalDateTime.now();
        long errors = appEventRepository.countBySeverityAndCreatedAtAfter(
                AppEventService.SEVERITY_ERROR, now.minusHours(1));
        if (errors > props.getErrorRatePerHour()) {
            // Same `now` for query window and de-dup key so a tick straddling an hour
            // boundary can't mark the new hour before its data has accumulated.
            fireOnce("error-spike", now.format(HOUR), () ->
                    "GastosAI alert: " + errors + " server errors in the last hour (threshold "
                            + props.getErrorRatePerHour() + ").");
        }
    }

    private void fireOnce(String key, String window, java.util.function.Supplier<String> message) {
        String previous = lastFired.put(key, window);
        if (!window.equals(previous)) {
            telegram.send(message.get());
        }
    }

    private String today() {
        return LocalDate.now().toString();
    }
}
