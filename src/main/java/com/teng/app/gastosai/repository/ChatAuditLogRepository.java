package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.ChatAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatAuditLogRepository extends JpaRepository<ChatAuditLog, Long> {

	List<ChatAuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);

	List<ChatAuditLog> findByOrderByCreatedAtDesc(Pageable pageable);
}
