package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class AdminDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminDataLoader.class);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final String adminEmail;
	private final String adminPassword;

	public AdminDataLoader(
			UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@Value("${gastos.admin.email:}") String adminEmail,
			@Value("${gastos.admin.password:}") String adminPassword) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (adminEmail.isBlank() || adminPassword.isBlank()) {
			log.debug("GASTOS_ADMIN_EMAIL / GASTOS_ADMIN_PASSWORD not configured — skipping admin account creation");
			return;
		}
		userRepository.findByEmail(adminEmail).orElseGet(() -> {
			User admin = User.builder()
					.name("Admin")
					.email(adminEmail)
					.password(passwordEncoder.encode(adminPassword))
					.role(Role.ADMIN)
					.build();
			User saved = userRepository.save(admin);
			log.info("Admin account ready — email: {}", adminEmail);
			return saved;
		});
	}
}
