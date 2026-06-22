package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.BudgetRule;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BudgetRuleRepository extends JpaRepository<BudgetRule, Long> {

    Optional<BudgetRule> findByUser(User user);
}
