package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanFeature;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.repository.PlanFeatureRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Seeds the default plans and their feature grants idempotently at startup, so the entitlement model
 * is consistent across every environment (including H2 tests, where Flyway is disabled). The
 * {@link FeatureKey} enum is the source of truth; PREMIUM grants all features, FREE a basic subset.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class EntitlementSeeder implements CommandLineRunner {

    // FREE gets the chatbot (bounded by the AI quota) so the "plain tone is free, professional/GenZ
    // tones are paid" split is meaningful, plus CSV export. Premium-only capabilities (standalone NL
    // analytics query, PDF export, forecasting, anomaly/advanced insights, premium chat personas) are
    // NOT granted here.
    private static final Set<FeatureKey> FREE_FEATURES = EnumSet.of(
            FeatureKey.EXPORT_CSV,
            FeatureKey.NL_CHATBOT);

    private static final Logger log = LoggerFactory.getLogger(EntitlementSeeder.class);

    private final SubscriptionPlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;

    @Override
    @Transactional
    public void run(String... args) {
        SubscriptionPlan free = ensurePlan(PlanKey.FREE, "Free", 0);
        SubscriptionPlan premium = ensurePlan(PlanKey.PREMIUM, "Premium", 14900); // TODO annual price ₱1290
        SubscriptionPlan trial = ensurePlan(PlanKey.TRIAL, "Trial", 0); // Premium-equivalent, time-boxed
        ensureFeatures(free, FREE_FEATURES);
        ensureFeatures(premium, EnumSet.allOf(FeatureKey.class));
        ensureFeatures(trial, EnumSet.allOf(FeatureKey.class));
    }

    private SubscriptionPlan ensurePlan(PlanKey key, String name, int priceCents) {
        return planRepository.findByPlanKey(key)
                .map(existing -> {
                    // Reconcile the seeded price/name so changes here roll out to already-seeded DBs.
                    if (existing.getPriceCents() != priceCents || !name.equals(existing.getName())) {
                        log.info("Updating subscription plan {} price {} -> {}", key, existing.getPriceCents(), priceCents);
                        existing.setName(name);
                        existing.setPriceCents(priceCents);
                        return planRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Seeding subscription plan {}", key);
                    return planRepository.save(SubscriptionPlan.builder()
                            .planKey(key).name(name).priceCents(priceCents).currency("PHP").active(true).build());
                });
    }

    private void ensureFeatures(SubscriptionPlan plan, Set<FeatureKey> features) {
        for (FeatureKey feature : features) {
            if (!planFeatureRepository.existsByPlanAndFeatureKey(plan, feature)) {
                planFeatureRepository.save(PlanFeature.builder().plan(plan).featureKey(feature).build());
            }
        }
    }
}
