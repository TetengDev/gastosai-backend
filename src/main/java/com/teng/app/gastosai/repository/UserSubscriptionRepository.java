package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {
    Optional<UserSubscription> findFirstByUserOrderByCreatedAtDesc(User user);
}
