package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.MonthSummaryInsightResponse;
import com.teng.app.gastosai.dto.RecommendationsInsightResponse;
import com.teng.app.gastosai.dto.TopCategoryInsightResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/ai/insights")
@RequiredArgsConstructor
public class AiInsightController {

    private final AiInsightService aiInsightService;

    @GetMapping("/top-category")
    public TopCategoryInsightResponse topCategory(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getTopCategory(user, month);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/month-summary")
    public MonthSummaryInsightResponse monthSummary(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getMonthSummary(user, month);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/recommendations")
    public RecommendationsInsightResponse recommendations(
            @RequestParam String month,
            @AuthenticationPrincipal User user) {
        validateMonth(month);
        try {
            return aiInsightService.getRecommendations(user, month);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void validateMonth(String month) {
        if (!month.matches("\\d{4}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }
}
