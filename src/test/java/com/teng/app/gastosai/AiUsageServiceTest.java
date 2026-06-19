package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.service.AiUsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock AiUsageRepository aiUsageRepository;
    @InjectMocks AiUsageService aiUsageService;

    @Test
    void recordsSuccessEntry() {
        when(aiUsageRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "openai", "gpt-4o-mini", AiFeature.CHAT_CRUD_ASSISTANT,
                100, 50, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        AiUsage saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo(AiUsageStatus.SUCCESS);
        assertThat(saved.getInputTokens()).isEqualTo(100);
        assertThat(saved.getOutputTokens()).isEqualTo(50);
        assertThat(saved.getTotalTokens()).isEqualTo(150);
        assertThat(saved.getErrorCode()).isNull();
    }

    @Test
    void recordsFailedEntry() {
        when(aiUsageRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(2L, "openai", "gpt-4o-mini", AiFeature.RECEIPT_ANALYSIS,
                null, null, AiUsageStatus.FAILED, "IOException");

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        AiUsage saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AiUsageStatus.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("IOException");
        assertThat(saved.getEstimatedCostUsd()).isNull();
    }

    @Test
    void estimatesCostForGpt4oMiniWhenTokensKnown() {
        when(aiUsageRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "openai", "gpt-4o-mini", AiFeature.MONTHLY_SUMMARY,
                1_000_000, 1_000_000, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        BigDecimal cost = captor.getValue().getEstimatedCostUsd();
        assertThat(cost).isNotNull();
        // $0.15 input + $0.60 output = $0.75
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.750000"));
    }

    @Test
    void nullTokensYieldNullCost() {
        when(aiUsageRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        aiUsageService.record(1L, "claude", "claude-3-5-sonnet", AiFeature.CHAT_CONTEXT_RESOLUTION,
                null, null, AiUsageStatus.SUCCESS, null);

        ArgumentCaptor<AiUsage> captor = ArgumentCaptor.forClass(AiUsage.class);
        verify(aiUsageRepository).save(captor.capture());
        assertThat(captor.getValue().getEstimatedCostUsd()).isNull();
    }
}
