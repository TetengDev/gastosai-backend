package com.teng.app.gastosai;

import com.teng.app.gastosai.config.ViewAsContext;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.EntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminViewAsEntitlementTest {

	@Autowired
	EntitlementService entitlementService;

	@Autowired
	UserRepository userRepository;

	User admin;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		admin = userRepository.save(User.builder()
				.name("Admin").email("admin-va@test.com").password("pw").role(Role.ADMIN).build());
	}

	@AfterEach
	void tearDown() {
		ViewAsContext.clear();
	}

	@Test
	void admin_hasAllFeatures_byDefault() {
		assertThat(entitlementService.canAccessFeature(admin, FeatureKey.ADVANCED_INSIGHTS)).isTrue();
		assertThat(entitlementService.describe(admin).admin()).isTrue();
	}

	@Test
	void viewAsFree_simulatesFreeTier() {
		ViewAsContext.set(PlanKey.FREE, null);
		// FREE grants EXPORT_CSV only (per EntitlementSeeder).
		assertThat(entitlementService.canAccessFeature(admin, FeatureKey.EXPORT_CSV)).isTrue();
		assertThat(entitlementService.canAccessFeature(admin, FeatureKey.ADVANCED_INSIGHTS)).isFalse();
		assertThat(entitlementService.describe(admin).plan()).isEqualTo(PlanKey.FREE);
	}

	@Test
	void viewAsPremium_unlocksAll() {
		ViewAsContext.set(PlanKey.PREMIUM, null);
		assertThat(entitlementService.canAccessFeature(admin, FeatureKey.ADVANCED_INSIGHTS)).isTrue();
	}
}
