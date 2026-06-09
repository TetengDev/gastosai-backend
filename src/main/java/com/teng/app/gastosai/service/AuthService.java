package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.dto.LoginRequest;
import com.teng.app.gastosai.dto.RegisterRequest;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
		}
		User user = User.builder()
				.name(request.name())
				.email(request.email())
				.password(passwordEncoder.encode(request.password()))
				.build();
		userRepository.save(user);
		return new AuthResponse(jwtUtil.generate(user.getEmail()), user.getEmail(), user.getName());
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
		if (!passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
		}
		return new AuthResponse(jwtUtil.generate(user.getEmail()), user.getEmail(), user.getName());
	}
}
