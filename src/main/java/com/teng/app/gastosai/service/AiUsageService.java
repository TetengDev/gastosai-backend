package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.AiFeature;
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

@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    // gpt-4o-mini pricing: $0.15/1M input tokens, $0.60/1M output tokens
    private static final BigDecimal GPT4O_MINI_INPUT_PER_TOKEN = new BigDecimal("0.00000015");
    private static final BigDecimal GPT4O_MINI_OUTPUT_PER_TOKEN = new BigDecimal("0.00000060");

    private final AiUsageRepository aiUsageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String provider, String model, AiFeature feature,
                       Integer inputTokens, Integer outputTokens, AiUsageStatus status, String errorCode) {
        Integer totalTokens;
        if (inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        } else {
            totalTokens = inputTokens != null ? inputTokens : outputTokens;
        }

        BigDecimal estimatedCostUsd = estimateCost(provider, model, inputTokens, outputTokens);

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

    private BigDecimal estimateCost(String provider, String model, Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        if (provider != null && provider.toLowerCase().contains("openai")
                && model != null && model.toLowerCase().contains("gpt-4o-mini")) {
            BigDecimal inputCost = GPT4O_MINI_INPUT_PER_TOKEN.multiply(new BigDecimal(inputTokens));
            BigDecimal outputCost = GPT4O_MINI_OUTPUT_PER_TOKEN.multiply(new BigDecimal(outputTokens));
            return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
        }
        return null;
    }
}
