package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.AppEventDto;
import com.teng.app.gastosai.dto.ObservabilityCost;
import com.teng.app.gastosai.dto.ObservabilityHealth;
import com.teng.app.gastosai.dto.ObservabilitySummary;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Aggregates operational + product metrics for the admin observability dashboard. */
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private static final int TOP_USERS = 5;

    private final UserRepository userRepository;
    private final AiUsageRepository aiUsageRepository;
    private final AppEventRepository appEventRepository;
    private final AiUsageService aiUsageService;
    private final ObjectProvider<BuildProperties> buildProperties;

    @Transactional(readOnly = true)
    public ObservabilitySummary summary() {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        LocalDateTime last7d = LocalDateTime.now().minusDays(7);
        LocalDateTime last30d = LocalDateTime.now().minusDays(30);
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);

        List<ObservabilitySummary.TopUserItem> topUsers = aiUsageRepository
                .topUsersByRequests(last30d, PageRequest.of(0, TOP_USERS)).stream()
                .map(row -> new ObservabilitySummary.TopUserItem(
                        row[0] != null ? ((Number) row[0]).longValue() : null,
                        ((Number) row[1]).longValue()))
                .toList();

        return new ObservabilitySummary(
                userRepository.count(),
                userRepository.countByCreatedAtAfter(startOfToday),
                userRepository.countByCreatedAtAfter(last7d),
                userRepository.countByCreatedAtAfter(last30d),
                aiUsageRepository.countDistinctActiveUsersSince(last24h),
                aiUsageRepository.countDistinctActiveUsersSince(last7d),
                topUsers);
    }

    @Transactional(readOnly = true)
    public ObservabilityCost cost() {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        BigDecimal todayCost = aiUsageRepository.sumEstimatedCostSince(startOfToday);
        return new ObservabilityCost(
                todayCost,
                aiUsageRepository.countByStatusAndCreatedAtAfter(AiUsageStatus.SUCCESS, startOfToday),
                aiUsageRepository.countByStatusAndCreatedAtAfter(AiUsageStatus.FAILED, startOfToday),
                aiUsageService.monthToDateSummary());
    }

    @Transactional(readOnly = true)
    public List<AppEventDto> events(String type, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        List<com.teng.app.gastosai.entity.AppEvent> rows = (type == null || type.isBlank())
                ? appEventRepository.findByOrderByCreatedAtDesc(page)
                : appEventRepository.findByEventTypeOrderByCreatedAtDesc(type, page);
        return rows.stream().map(AppEventDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ObservabilityHealth health() {
        boolean dbUp;
        try {
            userRepository.count();
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
        }
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new ObservabilityHealth(dbUp, version, uptimeSeconds);
    }
}
