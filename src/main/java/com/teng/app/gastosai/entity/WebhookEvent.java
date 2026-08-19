package com.teng.app.gastosai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A provider webhook event that has already been applied. One row per {@code (provider, eventId)},
 * and the unique constraint on that pair is what makes a repeated delivery a no-op: the second
 * copy of an event cannot insert its claim, so it never reaches the effect the first one had.
 *
 * <p>Written in the same transaction as that effect, so the ledger and what it records commit or
 * roll back together — a delivery that failed halfway must stay retryable.
 */
@Entity
@Table(
        name = "webhook_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_event_provider_event_id",
                columnNames = {"provider", "event_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    /** The provider's own id for the event — {@code data.id} in a PayMongo delivery. */
    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }
}
