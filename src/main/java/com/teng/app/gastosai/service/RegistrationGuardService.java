package com.teng.app.gastosai.service;

import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards against signup abuse: per-IP daily cap (in-memory day-bucket) and global daily
 * new-account cap (DB count). The in-memory IP bucket is per-JVM — on multi-instance
 * deployments each instance holds its own counter, so the effective per-IP limit is
 * N × REGISTER_IP_DAILY_MAX. Use Redis-backed counters before horizontal scaling.
 */
@Service
@RequiredArgsConstructor
public class RegistrationGuardService {

    private final UserRepository userRepository;

    @Value("${gastos.security.register-ip-daily-max:5}")
    private int ipDailyMax;

    @Value("${gastos.security.register-daily-max:100}")
    private int globalDailyMax;

    private final ConcurrentHashMap<String, IpBucket> ipBuckets = new ConcurrentHashMap<>();

    public void assertRegistrationAllowed(String clientIp) {
        assertIpQuota(clientIp);
        assertGlobalQuota();
    }

    private void assertIpQuota(String ip) {
        LocalDate today = LocalDate.now();
        if (ipBuckets.size() > 10_000) {
            ipBuckets.entrySet().removeIf(e -> !e.getValue().date.equals(today));
        }
        boolean[] exceeded = {false};
        ipBuckets.compute(ip, (k, existing) -> {
            if (existing == null || !existing.date.equals(today)) {
                return new IpBucket(today, 1);
            }
            if (existing.count >= ipDailyMax) {
                exceeded[0] = true;
                return existing;
            }
            existing.count++;
            return existing;
        });
        if (exceeded[0]) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many registrations from this address. Try again tomorrow.");
        }
    }

    @Transactional(readOnly = true)
    void assertGlobalQuota() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long todayCount = userRepository.countByCreatedAtAfter(startOfDay);
        if (todayCount >= globalDailyMax) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Registration is temporarily unavailable. Please try again later.");
        }
    }

    private static final class IpBucket {
        private LocalDate date;
        private int count;

        IpBucket(LocalDate date, int count) {
            this.date = date;
            this.count = count;
        }
    }
}
