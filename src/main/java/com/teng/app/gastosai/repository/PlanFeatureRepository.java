package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanFeature;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanFeatureRepository extends JpaRepository<PlanFeature, Long> {

    boolean existsByPlanAndFeatureKey(SubscriptionPlan plan, FeatureKey featureKey);

    List<PlanFeature> findAllByPlan(SubscriptionPlan plan);
}
