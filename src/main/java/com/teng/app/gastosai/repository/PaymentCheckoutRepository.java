package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentCheckoutRepository extends JpaRepository<PaymentCheckout, Long> {

    Optional<PaymentCheckout> findBySessionId(String sessionId);

    Optional<PaymentCheckout> findFirstByUserAndStatusOrderByCreatedAtDesc(User user, CheckoutStatus status);
}
