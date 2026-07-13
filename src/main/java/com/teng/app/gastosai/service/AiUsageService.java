package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiCostProperties;
import com.teng.app.gastosai.dto.AiUsageSummaryItem;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.AiUsageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final AiUsageRepository aiUsageRepository;
    private final AiCostProperties costProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String provider, String model, AiFeature feature,
                       Integer inputTokens, Integer outputTokens, AiUsageStatus status, String errorCode) {
        Integer totalTokens;
        if (inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        } else {
            totalTokens = inputTokens != null ? inputTokens : outputTokens;
        }

        BigDecimal estimatedCostUsd = estimateCost(inputTokens, outputTokens);

        AiUsage usage = AiUsage.builder()
                .userId(userId)
                .provider(provider)
                .model(model)
                .feature(feature)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .estimatedCostUsd(estimatedCostUsd)
                .status(status)
                .errorCode(errorCode)
                .build();

        try {
            aiUsageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to record AI usage for user {}: {}", userId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AiUsageSummaryItem> monthToDateSummary() {
        LocalDateTime since = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return aiUsageRepository.summarizeMonthToDate(since).stream()
                .map(row -> new AiUsageSummaryItem(
                        row[0] instanceof AiFeature f ? f.name() : String.valueOf(row[0]),
                        (String) row[1],
                        ((Number) row[2]).longValue(),
                        row[3] != null ? ((Number) row[3]).longValue() : null,
                        row[4] != null ? ((Number) row[4]).longValue() : null,
                        row[5] != null ? ((BigDecimal) row[5]).setScale(6, RoundingMode.HALF_UP) : null
                ))
                .toList();
    }

    private BigDecimal estimateCost(Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        BigDecimal inputRate = BigDecimal.valueOf(costProperties.getInputPerMtokUsd()).divide(MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal outputRate = BigDecimal.valueOf(costProperties.getOutputPerMtokUsd()).divide(MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal inputCost = inputRate.multiply(new BigDecimal(inputTokens));
        BigDecimal outputCost = outputRate.multiply(new BigDecimal(outputTokens));
        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
    }
}
