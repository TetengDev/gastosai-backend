package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ObservabilityHealth;
import com.teng.app.gastosai.dto.ObservabilitySummary;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiUsageService;
import com.teng.app.gastosai.service.ObservabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObservabilityServiceTest {

    @Mock UserRepository userRepository;
    @Mock AiUsageRepository aiUsageRepository;
    @Mock AppEventRepository appEventRepository;
    @Mock AiUsageService aiUsageService;

    private ObservabilityService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(null);
        return new ObservabilityService(userRepository, aiUsageRepository, appEventRepository, aiUsageService, provider);
    }

    @Test
    void summary_mapsTopUsersFromRows() {
        lenient().when(userRepository.count()).thenReturn(3L);
        when(aiUsageRepository.topUsersByRequests(any(), any()))
                .thenReturn(List.of(new Object[]{5L, 12L}, new Object[]{null, 4L}));

        ObservabilitySummary summary = service().summary();

        assertThat(summary.topUsers30d()).hasSize(2);
        assertThat(summary.topUsers30d().get(0).userId()).isEqualTo(5L);
        assertThat(summary.topUsers30d().get(0).requests()).isEqualTo(12L);
        assertThat(summary.topUsers30d().get(1).userId()).isNull();
    }

    @Test
    void events_clampsLimitToUpperBound() {
        when(appEventRepository.findByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        service().events(null, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(appEventRepository).findByOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    void events_clampsLimitToLowerBound() {
        when(appEventRepository.findByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        service().events(null, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(appEventRepository).findByOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void events_withType_usesTypeFilteredQuery() {
        when(appEventRepository.findByEventTypeOrderByCreatedAtDesc(anyString(), any())).thenReturn(List.of());

        service().events("SERVER_ERROR", 50);

        verify(appEventRepository).findByEventTypeOrderByCreatedAtDesc(anyString(), any());
    }

    @Test
    void health_dbReachable_reportsUpWithDevVersionFallback() {
        when(userRepository.count()).thenReturn(1L);

        ObservabilityHealth health = service().health();

        assertThat(health.dbUp()).isTrue();
        assertThat(health.version()).isEqualTo("dev");
        assertThat(health.uptimeSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void health_dbDown_reportsDown() {
        when(userRepository.count()).thenThrow(new RuntimeException("db down"));

        ObservabilityHealth health = service().health();

        assertThat(health.dbUp()).isFalse();
    }
}
