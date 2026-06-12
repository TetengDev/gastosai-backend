package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(User user, String month);

    Optional<Alert> findByUserAndTypeAndMonthAndCategoryName(User user, AlertType type, String month, String categoryName);

    Optional<Alert> findByIdAndUser(Long id, User user);
}
