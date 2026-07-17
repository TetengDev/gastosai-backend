package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.AppEvent;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.service.AppEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppEventServiceTest {

    private final AppEventRepository repository = mock(AppEventRepository.class);
    private final AppEventService service = new AppEventService(repository);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recordAbuseTrip_savesWarnEventWith429() {
        service.recordAbuseTrip("WRITE_RATE_LIMIT", 7L, "/expenses", "slow down");

        ArgumentCaptor<AppEvent> captor = ArgumentCaptor.forClass(AppEvent.class);
        verify(repository).save(captor.capture());
        AppEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("WRITE_RATE_LIMIT");
        assertThat(saved.getSeverity()).isEqualTo("WARN");
        assertThat(saved.getHttpStatus()).isEqualTo(429);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getPath()).isEqualTo("/expenses");
    }

    @Test
    void record_picksUpRequestIdFromMdc() {
        MDC.put("requestId", "abc-123");
        service.record("SERVER_ERROR", AppEventService.SEVERITY_ERROR, null, "/x", 500, "boom", "detail");

        ArgumentCaptor<AppEvent> captor = ArgumentCaptor.forClass(AppEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRequestId()).isEqualTo("abc-123");
    }

    @Test
    void record_truncatesOverlongMessage() {
        String longMsg = "x".repeat(900);
        service.record("SERVER_ERROR", AppEventService.SEVERITY_ERROR, null, "/x", 500, longMsg, null);

        ArgumentCaptor<AppEvent> captor = ArgumentCaptor.forClass(AppEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMessage()).hasSize(500);
    }

    @Test
    void record_truncatesOverlongPath() {
        String longPath = "/" + "a".repeat(300);
        service.record("SERVER_ERROR", AppEventService.SEVERITY_ERROR, null, longPath, 500, "m", null);

        ArgumentCaptor<AppEvent> captor = ArgumentCaptor.forClass(AppEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPath()).hasSize(200);
    }

    @Test
    void record_swallowsRepositoryFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() ->
                service.record("SERVER_ERROR", AppEventService.SEVERITY_ERROR, null, "/x", 500, "m", null))
                .doesNotThrowAnyException();
    }
}
