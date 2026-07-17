package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AppEventService;
import com.teng.app.gastosai.service.RegistrationGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationGuardServiceUnitTest {

    UserRepository userRepository;
    RegistrationGuardService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        service = new RegistrationGuardService(userRepository, mock(AppEventService.class));
        ReflectionTestUtils.setField(service, "ipDailyMax", 3);
        ReflectionTestUtils.setField(service, "globalDailyMax", 100);
    }

    @Test
    void ipQuota_blocksAfterLimit() {
        service.assertRegistrationAllowed("10.0.0.1");
        service.assertRegistrationAllowed("10.0.0.1");
        service.assertRegistrationAllowed("10.0.0.1");
        assertThatThrownBy(() -> service.assertRegistrationAllowed("10.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Too many registrations");
    }

    @Test
    void ipQuota_differentIps_independent() {
        for (int i = 0; i < 3; i++) {
            service.assertRegistrationAllowed("10.0.0.1");
        }
        service.assertRegistrationAllowed("10.0.0.2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleBuckets_evictedWhenMapExceeds10k() throws Exception {
        ConcurrentHashMap<String, Object> ipBuckets =
                (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(service, "ipBuckets");

        Class<?> ipBucketClass = null;
        for (Class<?> inner : RegistrationGuardService.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("IpBucket")) {
                ipBucketClass = inner;
                break;
            }
        }
        assertThat(ipBucketClass).isNotNull();

        var ctor = ipBucketClass.getDeclaredConstructor(LocalDate.class, int.class);
        ctor.setAccessible(true);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (int i = 0; i < 10_001; i++) {
            ipBuckets.put("stale-" + i, ctor.newInstance(yesterday, 1));
        }
        assertThat(ipBuckets).hasSize(10_001);

        service.assertRegistrationAllowed("trigger-eviction");

        assertThat(ipBuckets.size()).isLessThan(10_001);
        assertThat(ipBuckets.keySet()).doesNotContain("stale-0", "stale-1");
    }
}
