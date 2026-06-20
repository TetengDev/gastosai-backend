package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.MonthSummaryInsightResponse;
import com.teng.app.gastosai.dto.RecommendationsInsightResponse;
import com.teng.app.gastosai.dto.TopCategoryInsightResponse;
import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.service.AiInsightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/ai/insights")
@RequiredArgsConstructor
public class AiInsightController {

    private static final String GENERIC_ERROR = "Failed to generate insight. Please try again later.";

    private final AiInsightService aiInsightService;

    @GetMapping("/top-category")
    @RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
    public TopCategoryInsightResponse topCategory(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getTopCategory(user, month);
        } catch (ResponseStatusException | AiQuotaExceededException | FeatureLockedException e) {
            throw e;
        } catch (Exception e) {
            log.error("ai_insight_failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
        }
    }

    @GetMapping("/month-summary")
    @RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
    public MonthSummaryInsightResponse monthSummary(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getMonthSummary(user, month);
        } catch (ResponseStatusException | AiQuotaExceededException | FeatureLockedException e) {
            throw e;
        } catch (Exception e) {
            log.error("ai_insight_failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
        }
    }

    @GetMapping("/recommendations")
    @RequiresFeature(FeatureKey.ADVANCED_INSIGHTS)
    public RecommendationsInsightResponse recommendations(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getRecommendations(user, month);
        } catch (ResponseStatusException | AiQuotaExceededException | FeatureLockedException e) {
            throw e;
        } catch (Exception e) {
            log.error("ai_insight_failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR);
        }
    }

    private void validateMonth(String month) {
        if (!month.matches("\\d{4}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }
}
