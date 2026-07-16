package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.AppEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AppEventRepository extends JpaRepository<AppEvent, Long> {

    List<AppEvent> findByOrderByCreatedAtDesc(Pageable pageable);

    List<AppEvent> findByEventTypeOrderByCreatedAtDesc(String eventType, Pageable pageable);

    List<AppEvent> findBySeverityOrderByCreatedAtDesc(String severity, Pageable pageable);

    long countBySeverityAndCreatedAtAfter(String severity, LocalDateTime after);
}
