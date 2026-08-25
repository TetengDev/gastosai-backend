package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.MerchantRule;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantRuleRepository extends JpaRepository<MerchantRule, Long> {

	Optional<MerchantRule> findByUserAndMerchant(User user, String merchant);

	List<MerchantRule> findAllByUser(User user);

	List<MerchantRule> findAllByUserAndCategory_Id(User user, Long categoryId);

	void deleteByUserAndMerchant(User user, String merchant);
}
