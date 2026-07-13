package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {

    long countByUserIdAndStatusAndCreatedAtAfter(Long userId, AiUsageStatus status, LocalDateTime after);

    long countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(Long userId, AiUsageStatus status, Collection<AiFeature> features, LocalDateTime after);

    long countByUserIdAndFeatureAndStatusAndCreatedAtAfter(Long userId, AiFeature feature, AiUsageStatus status, LocalDateTime after);

    @Query("""
            SELECT a.feature, a.model,
                   COUNT(a) AS requests,
                   SUM(a.inputTokens) AS inputTokens,
                   SUM(a.outputTokens) AS outputTokens,
                   SUM(a.estimatedCostUsd) AS estimatedCostUsd
            FROM AiUsage a
            WHERE a.createdAt >= :since
            GROUP BY a.feature, a.model
            ORDER BY a.feature, a.model
            """)
    List<Object[]> summarizeMonthToDate(@Param("since") LocalDateTime since);
}
