package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {

    long countByUserIdAndStatusAndCreatedAtAfter(Long userId, AiUsageStatus status, LocalDateTime after);

    long countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(Long userId, AiUsageStatus status, Collection<AiFeature> features, LocalDateTime after);

    long countByUserIdAndFeatureAndStatusAndCreatedAtAfter(Long userId, AiFeature feature, AiUsageStatus status, LocalDateTime after);

    long countByStatusAndCreatedAtAfter(AiUsageStatus status, LocalDateTime after);

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

    @Query("SELECT COUNT(DISTINCT a.userId) FROM AiUsage a WHERE a.createdAt >= :since")
    long countDistinctActiveUsersSince(@Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(a.estimatedCostUsd), 0) FROM AiUsage a WHERE a.createdAt >= :since")
    BigDecimal sumEstimatedCostSince(@Param("since") LocalDateTime since);

    @Query("""
            SELECT a.userId, COUNT(a) AS requests
            FROM AiUsage a
            WHERE a.createdAt >= :since
            GROUP BY a.userId
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> topUsersByRequests(@Param("since") LocalDateTime since, Pageable pageable);
}
