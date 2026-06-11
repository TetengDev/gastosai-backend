package com.teng.app.gastosai.dto;

import java.util.List;

public record RecommendationsInsightResponse(String month, List<String> recommendations) {}