package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {
	List<SavingsGoal> findAllByUserOrderByCreatedAtDesc(User user);
	Optional<SavingsGoal> findByIdAndUser(Long id, User user);
	boolean existsByUserAndNameIgnoreCase(User user, String name);
}
