package com.teng.app.gastosai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;

/**
 * Operational event log for observability: captured server errors (5xx) and abuse-guard
 * trips. Persisted (not in-memory) so the record survives Render free-tier sleep/restart.
 * {@code user_id} is a plain column with no FK — an event may reference a since-deleted
 * user or none at all, and audit history must outlive the user row.
 */
@Entity
@Table(name = "app_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 10)
    private String severity;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 200)
    private String path;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(length = 500)
    private String message;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
