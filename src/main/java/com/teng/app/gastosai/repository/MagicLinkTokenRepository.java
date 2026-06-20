package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.MagicLinkToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, Long> {

    Optional<MagicLinkToken> findByTokenHash(String tokenHash);

    @Query("""
            SELECT COUNT(t) FROM MagicLinkToken t
            WHERE t.email = :email
              AND t.usedAt IS NULL
              AND t.expiresAt > :since
            """)
    long countUnusedSince(@Param("email") String email, @Param("since") LocalDateTime since);
}
