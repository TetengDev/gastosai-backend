package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AlertProperties;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.service.AlertScheduler;
import com.teng.app.gastosai.service.TelegramAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSchedulerTest {

    @Mock TelegramAlertService telegram;
    @Mock AiUsageRepository aiUsageRepository;
    @Mock AppEventRepository appEventRepository;

    AlertProperties props;
    AiManagedProperties managedProps;
    AlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new AlertProperties();
        props.setEnabled(true);
        props.setBotToken("token");
        props.setChatId("chat");
        props.setDailyCostUsd(new BigDecimal("1.00"));
        props.setErrorRatePerHour(5);
        managedProps = new AiManagedProperties();
        scheduler = new AlertScheduler(props, telegram, aiUsageRepository, appEventRepository, managedProps);

        lenient().when(aiUsageRepository.sumEstimatedCostSince(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(aiUsageRepository.countByStatusAndCreatedAtAfter(any(AiUsageStatus.class), any())).thenReturn(0L);
        lenient().when(appEventRepository.countBySeverityAndCreatedAtAfter(anyString(), any())).thenReturn(0L);
    }

    @Test
    void disabled_sendsNothing() {
        props.setEnabled(false);

        scheduler.runChecks();

        verifyNoInteractions(telegram);
    }

    @Test
    void noCreds_sendsNothing() {
        props.setBotToken("");

        scheduler.runChecks();

        verifyNoInteractions(telegram);
    }

    @Test
    void underThresholds_sendsNothing() {
        scheduler.runChecks();
        verifyNoInteractions(telegram);
    }

    @Test
    void dailyCostBreach_alertsOncePerDay() {
        when(aiUsageRepository.sumEstimatedCostSince(any())).thenReturn(new BigDecimal("2.50"));

        scheduler.runChecks();
        scheduler.runChecks(); // same day -> de-duped

        verify(telegram, times(1)).send(contains("AI spend"));
    }

    @Test
    void errorSpikeBreach_alerts() {
        when(appEventRepository.countBySeverityAndCreatedAtAfter(anyString(), any())).thenReturn(9L);

        scheduler.runChecks();

        verify(telegram).send(contains("server errors"));
    }

    @Test
    void globalCapBreach_alerts() {
        long max = managedProps.getGlobalDailyMax();
        when(aiUsageRepository.countByStatusAndCreatedAtAfter(any(AiUsageStatus.class), any())).thenReturn(max);

        scheduler.runChecks();

        verify(telegram).send(contains("global daily AI usage"));
    }

    @Test
    void belowGlobalCapFraction_noAlert() {
        long belowWarn = (long) (managedProps.getGlobalDailyMax() * 0.5);
        when(aiUsageRepository.countByStatusAndCreatedAtAfter(any(AiUsageStatus.class), any())).thenReturn(belowWarn);

        scheduler.runChecks();

        verify(telegram, never()).send(contains("global daily AI usage"));
    }
}
