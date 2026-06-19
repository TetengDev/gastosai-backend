package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.CategoryReportItem;
import com.teng.app.gastosai.dto.MonthSummaryInsightResponse;
import com.teng.app.gastosai.dto.RecommendationsInsightResponse;
import com.teng.app.gastosai.dto.TopCategoryInsightResponse;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiInsightService {

    private final ExpenseService expenseService;
    private final SqlGenerator sqlGenerator;
    private final ObjectMapper objectMapper;
    private final AiUsageService aiUsageService;
    private final AiProviderProperties aiProviderProperties;
    private final OpenAiProperties openAiProperties;
    private final ClaudeProperties claudeProperties;

    @Cacheable(cacheNames = "insightTopCategory", key = "#user.id + '-' + #month")
    @Transactional(readOnly = true)
    public TopCategoryInsightResponse getTopCategory(User user, String month) {
        List<CategoryReportItem> categories = expenseService.categoryReportForMonth(user, month);
        if (categories.isEmpty()) {
            return new TopCategoryInsightResponse(month, "None", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        CategoryReportItem top = categories.stream()
                .max((a, b) -> a.total().compareTo(b.total()))
                .orElseThrow();
        BigDecimal currentTotal = expenseService.monthlyComparison(user, month).currentTotal();
        BigDecimal percent = currentTotal.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : top.total()
                        .divide(currentTotal, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
        return new TopCategoryInsightResponse(month, top.category(),
                top.total().setScale(2, RoundingMode.HALF_UP), percent);
    }

    @Cacheable(cacheNames = "insightMonthSummary", key = "#user.id + '-' + #month")
    @Transactional(readOnly = true)
    public MonthSummaryInsightResponse getMonthSummary(User user, String month) throws Exception {
        String contextJson = buildContext(user, month);
        try {
            String summary = sqlGenerator.generateInsightSummary(contextJson, "month-summary", "plain");
            // best-effort: provider usage not surfaced here yet (TODO wire tokens)
            aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
                    resolveModel(), AiFeature.MONTHLY_SUMMARY,
                    null, null, AiUsageStatus.SUCCESS, null);
            return new MonthSummaryInsightResponse(month, summary);
        } catch (Exception e) {
            aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
                    resolveModel(), AiFeature.MONTHLY_SUMMARY,
                    null, null, AiUsageStatus.FAILED, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Cacheable(cacheNames = "insightRecommendations", key = "#user.id + '-' + #month")
    @Transactional(readOnly = true)
    public RecommendationsInsightResponse getRecommendations(User user, String month) throws Exception {
        String contextJson = buildContext(user, month);
        try {
            String raw = sqlGenerator.generateInsightSummary(contextJson, "recommendations", "plain");
            List<String> recs;
            try {
                recs = objectMapper.readValue(raw, new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                recs = List.of(raw);
            }
            // best-effort: provider usage not surfaced here yet (TODO wire tokens)
            aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
                    resolveModel(), AiFeature.BUDGET_ADVICE,
                    null, null, AiUsageStatus.SUCCESS, null);
            return new RecommendationsInsightResponse(month, recs);
        } catch (Exception e) {
            aiUsageService.record(user.getId(), aiProviderProperties.getProvider(),
                    resolveModel(), AiFeature.BUDGET_ADVICE,
                    null, null, AiUsageStatus.FAILED, e.getClass().getSimpleName());
            throw e;
        }
    }

    private String resolveModel() {
        return "claude".equalsIgnoreCase(aiProviderProperties.getProvider())
                ? claudeProperties.getModel()
                : openAiProperties.getModel();
    }

    private String buildContext(User user, String month) throws Exception {
        var comparison = expenseService.monthlyComparison(user, month);
        var categories = expenseService.categoryReportForMonth(user, month);
        List<Map<String, Object>> top5 = categories.stream()
                .limit(5)
                .map(c -> {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("category", c.category());
                    entry.put("total", c.total());
                    return entry;
                })
                .toList();
        Map<String, Object> context = new HashMap<>();
        context.put("month", month);
        context.put("currentTotal", comparison.currentTotal() != null ? comparison.currentTotal() : BigDecimal.ZERO);
        context.put("previousTotal", comparison.previousTotal() != null ? comparison.previousTotal() : BigDecimal.ZERO);
        context.put("changePercent", comparison.changePercent() != null ? comparison.changePercent() : BigDecimal.ZERO);
        context.put("topCategories", top5);
        return objectMapper.writeValueAsString(context);
    }
}
