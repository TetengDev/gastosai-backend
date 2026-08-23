package com.teng.app.gastosai.service;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.AiCostProperties;
import com.teng.app.gastosai.config.JacksonTimeConfig;
import com.teng.app.gastosai.dto.AiCostBreakdown;
import com.teng.app.gastosai.dto.AiCostByPlanItem;
import com.teng.app.gastosai.dto.AiCostByUserItem;
import com.teng.app.gastosai.dto.AiCostPricing;
import com.teng.app.gastosai.dto.AiCostReport;
import com.teng.app.gastosai.dto.AiUsageSummaryItem;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.repository.AiUsageRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final int RATE_SCALE = 10;
    private static final int USD_SCALE = 6;

    private final AiUsageRepository aiUsageRepository;
    private final AiCostProperties costProperties;
    private final EntityManager entityManager;
    private final BigDecimal visionInputPerMtokUsd;
    private final BigDecimal visionOutputPerMtokUsd;
    private final LocalDate pricesLastCheckedOn;
    private final String pricesSource;

    /**
     * The vision rates and the price-checked date arrive as plain properties rather than through
     * {@link AiCostProperties}, which still owns the text rates. They live under the same
     * {@code gastos.ai.cost.*} prefix in {@code application.properties}.
     */
    public AiUsageService(
            AiUsageRepository aiUsageRepository,
            AiCostProperties costProperties,
            EntityManager entityManager,
            @Value("${gastos.ai.cost.vision-input-per-mtok-usd:2.50}") double visionInputPerMtokUsd,
            @Value("${gastos.ai.cost.vision-output-per-mtok-usd:10.00}") double visionOutputPerMtokUsd,
            @Value("${gastos.ai.cost.prices-checked-on:2026-08-24}") LocalDate pricesLastCheckedOn,
            @Value("${gastos.ai.cost.prices-source:OpenAI API pricing page}") String pricesSource) {
        this.aiUsageRepository = aiUsageRepository;
        this.costProperties = costProperties;
        this.entityManager = entityManager;
        this.visionInputPerMtokUsd = BigDecimal.valueOf(visionInputPerMtokUsd);
        this.visionOutputPerMtokUsd = BigDecimal.valueOf(visionOutputPerMtokUsd);
        this.pricesLastCheckedOn = pricesLastCheckedOn;
        this.pricesSource = pricesSource;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String provider, String model, AiFeature feature,
                       Integer inputTokens, Integer outputTokens, AiUsageStatus status, String errorCode) {
        Integer totalTokens;
        if (inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        } else {
            totalTokens = inputTokens != null ? inputTokens : outputTokens;
        }

        BigDecimal estimatedCostUsd = estimateCost(feature, inputTokens, outputTokens);

        AiUsage usage = AiUsage.builder()
                .userId(userId)
                .provider(provider)
                .model(model)
                .feature(feature)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .estimatedCostUsd(estimatedCostUsd)
                .status(status)
                .errorCode(errorCode)
                .build();

        try {
            aiUsageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to record AI usage for user {}: {}", userId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AiUsageSummaryItem> monthToDateSummary() {
        LocalDateTime since = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return aiUsageRepository.summarizeMonthToDate(since).stream()
                .map(row -> new AiUsageSummaryItem(
                        row[0] instanceof AiFeature f ? f.name() : String.valueOf(row[0]),
                        (String) row[1],
                        ((Number) row[2]).longValue(),
                        row[3] != null ? ((Number) row[3]).longValue() : null,
                        row[4] != null ? ((Number) row[4]).longValue() : null,
                        row[5] != null ? ((BigDecimal) row[5]).setScale(USD_SCALE, RoundingMode.HALF_UP) : null
                ))
                .toList();
    }

    /**
     * Cost to serve for a period, per user and per plan, with text and vision kept apart.
     *
     * <p>Both halves of this report — the usage rollup and each user's current plan — are queried
     * here rather than from a repository because the report spans two aggregates that neither
     * repository owns on its own: it is a reporting projection, not the persistence surface of an
     * entity.
     *
     * <p>Failed calls are included. A request that reached the provider and came back an error was
     * still billed, and a report that dropped it would understate the cost of exactly the traffic
     * worth fixing. Rows with no recorded tokens contribute nothing either way.
     *
     * @param from first day counted, inclusive; defaults to the first of the current Manila month
     * @param to   last day counted, inclusive; defaults to today in Manila
     */
    @Transactional(readOnly = true)
    public AiCostReport costReport(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(JacksonTimeConfig.APP_ZONE);
        LocalDate periodStart = from != null ? from : today.withDayOfMonth(1);
        LocalDate periodEnd = to != null ? to : today;
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }

        Map<Long, PlanKey> plans = currentPlanByUser();
        Map<Long, Slices> byUserId = new LinkedHashMap<>();
        Map<PlanKey, Slices> byPlan = new EnumMap<>(PlanKey.class);
        Map<PlanKey, Long> activeUsersByPlan = new EnumMap<>(PlanKey.class);

        for (Object[] row : rollupByUserAndFeature(periodStart.atStartOfDay(),
                periodEnd.plusDays(1).atStartOfDay())) {
            Long userId = ((Number) row[0]).longValue();
            AiFeature feature = (AiFeature) row[1];
            long requests = ((Number) row[2]).longValue();
            long inputTokens = ((Number) row[3]).longValue();
            long outputTokens = ((Number) row[4]).longValue();

            PlanKey plan = plans.getOrDefault(userId, PlanKey.FREE);
            boolean firstRowForUser = !byUserId.containsKey(userId);
            byUserId.computeIfAbsent(userId, id -> new Slices())
                    .add(feature.isVision(), requests, inputTokens, outputTokens);
            byPlan.computeIfAbsent(plan, p -> new Slices())
                    .add(feature.isVision(), requests, inputTokens, outputTokens);
            if (firstRowForUser) {
                activeUsersByPlan.merge(plan, 1L, Long::sum);
            }
        }

        List<AiCostByUserItem> users = byUserId.entrySet().stream()
                .map(e -> {
                    AiCostBreakdown text = e.getValue().text.toBreakdown(false);
                    AiCostBreakdown vision = e.getValue().vision.toBreakdown(true);
                    return new AiCostByUserItem(
                            e.getKey(),
                            plans.getOrDefault(e.getKey(), PlanKey.FREE).name(),
                            text,
                            vision,
                            text.costUsd().add(vision.costUsd()));
                })
                .sorted(Comparator.comparing(AiCostByUserItem::totalCostUsd).reversed()
                        .thenComparing(AiCostByUserItem::userId))
                .toList();

        List<AiCostByPlanItem> plansReport = byPlan.entrySet().stream()
                .map(e -> {
                    AiCostBreakdown text = e.getValue().text.toBreakdown(false);
                    AiCostBreakdown vision = e.getValue().vision.toBreakdown(true);
                    BigDecimal total = text.costUsd().add(vision.costUsd());
                    long activeUsers = activeUsersByPlan.getOrDefault(e.getKey(), 0L);
                    BigDecimal perUser = activeUsers == 0
                            ? BigDecimal.ZERO.setScale(USD_SCALE)
                            : total.divide(BigDecimal.valueOf(activeUsers), USD_SCALE, RoundingMode.HALF_UP);
                    return new AiCostByPlanItem(e.getKey().name(), activeUsers, text, vision, total, perUser);
                })
                .sorted(Comparator.comparing(AiCostByPlanItem::plan))
                .toList();

        return new AiCostReport(periodStart, periodEnd, pricing(), plansReport, users);
    }

    /** The prices this report multiplies tokens by, and when a human last checked them. */
    public AiCostPricing pricing() {
        return new AiCostPricing(
                BigDecimal.valueOf(costProperties.getInputPerMtokUsd()),
                BigDecimal.valueOf(costProperties.getOutputPerMtokUsd()),
                visionInputPerMtokUsd,
                visionOutputPerMtokUsd,
                pricesLastCheckedOn,
                pricesSource);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rollupByUserAndFeature(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return entityManager.createQuery("""
                        SELECT a.userId, a.feature,
                               COUNT(a),
                               COALESCE(SUM(a.inputTokens), 0),
                               COALESCE(SUM(a.outputTokens), 0)
                        FROM AiUsage a
                        WHERE a.createdAt >= :from AND a.createdAt < :to
                        GROUP BY a.userId, a.feature
                        ORDER BY a.userId, a.feature
                        """, Object[].class)
                .setParameter("from", fromInclusive)
                .setParameter("to", toExclusive)
                .getResultList();
    }

    /**
     * Each user's plan as it stands now. Only {@code ACTIVE} and {@code TRIAL} subscriptions grant
     * a plan; everyone else — including users with an expired or cancelled row — reads as
     * {@code FREE}. Where a user somehow holds more than one, the paid plan wins, so a paid user
     * is never reported under {@code FREE}.
     */
    private Map<Long, PlanKey> currentPlanByUser() {
        List<Object[]> rows = entityManager.createQuery("""
                SELECT s.user.id, s.plan.planKey
                FROM UserSubscription s
                WHERE s.status IN (com.teng.app.gastosai.entity.SubscriptionStatus.ACTIVE,
                                   com.teng.app.gastosai.entity.SubscriptionStatus.TRIAL)
                """, Object[].class).getResultList();

        Map<Long, PlanKey> plans = new HashMap<>();
        for (Object[] row : rows) {
            Long userId = ((Number) row[0]).longValue();
            PlanKey plan = (PlanKey) row[1];
            plans.merge(userId, plan, (a, b) -> rank(a) >= rank(b) ? a : b);
        }
        return plans;
    }

    private static int rank(PlanKey plan) {
        return switch (plan) {
            case PREMIUM -> 2;
            case TRIAL -> 1;
            case FREE -> 0;
        };
    }

    private BigDecimal estimateCost(AiFeature feature, Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        boolean vision = feature != null && feature.isVision();
        return cost(vision, inputTokens, outputTokens);
    }

    /**
     * Tokens to USD at this instance's configured rates. Vision calls price at their own rates:
     * an image is billed as a large block of prompt tokens at a rate an order of magnitude above
     * text, so charging receipt analysis at the text rate understates it badly.
     */
    private BigDecimal cost(boolean vision, long inputTokens, long outputTokens) {
        BigDecimal inputPerMtok = vision ? visionInputPerMtokUsd : BigDecimal.valueOf(costProperties.getInputPerMtokUsd());
        BigDecimal outputPerMtok = vision ? visionOutputPerMtokUsd : BigDecimal.valueOf(costProperties.getOutputPerMtokUsd());
        BigDecimal inputCost = inputPerMtok.divide(MILLION, RATE_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal outputCost = outputPerMtok.divide(MILLION, RATE_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(outputTokens));
        return inputCost.add(outputCost).setScale(USD_SCALE, RoundingMode.HALF_UP);
    }

    /** Mutable text/vision accumulator used while folding the rollup rows. */
    private final class Slices {
        private final Slice text = new Slice();
        private final Slice vision = new Slice();

        void add(boolean isVision, long requests, long inputTokens, long outputTokens) {
            (isVision ? vision : text).add(requests, inputTokens, outputTokens);
        }
    }

    private final class Slice {
        private long requests;
        private long inputTokens;
        private long outputTokens;

        void add(long requests, long inputTokens, long outputTokens) {
            this.requests += requests;
            this.inputTokens += inputTokens;
            this.outputTokens += outputTokens;
        }

        AiCostBreakdown toBreakdown(boolean vision) {
            return new AiCostBreakdown(requests, inputTokens, outputTokens,
                    cost(vision, inputTokens, outputTokens));
        }
    }
}
