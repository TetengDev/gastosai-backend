package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {

    long countByUserIdAndStatusAndCreatedAtAfter(Long userId, AiUsageStatus status, LocalDateTime after);

    long countByUserIdAndStatusAndFeatureInAndCreatedAtAfter(Long userId, AiUsageStatus status, Collection<AiFeature> features, LocalDateTime after);

    long countByUserIdAndFeatureAndStatusAndCreatedAtAfter(Long userId, AiFeature feature, AiUsageStatus status, LocalDateTime after);
}
