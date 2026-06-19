package com.teng.app.gastosai.entity;

import com.teng.app.gastosai.ai.AiFeature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiFeature feature;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "estimated_cost_usd", precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AiUsageStatus status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
