package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
