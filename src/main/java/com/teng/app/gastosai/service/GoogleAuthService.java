package com.teng.app.gastosai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Verifies a Google Identity Services ID token and logs the user in (creating the account on first
 * sign-in). Scaffold: disabled until {@code gastos.auth.google.client-id} (env GOOGLE_CLIENT_ID) is
 * set, so the frontend hides the button until then. Verification uses Google's tokeninfo endpoint;
 * swap in google-api-client for offline signature checks when hardening.
 */
@Slf4j
@Service
public class GoogleAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final CategorySeedService categorySeedService;
    private final RestClient googleClient;
    private final String clientId;

    public GoogleAuthService(UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             AuthService authService,
                             CategorySeedService categorySeedService,
                             @Value("${gastos.auth.google.client-id:}") String clientId) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.categorySeedService = categorySeedService;
        this.clientId = clientId;
        this.googleClient = RestClient.create("https://oauth2.googleapis.com");
    }

    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank();
    }

    @Transactional
    public AuthResponse loginWithIdToken(String idToken) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured");
        }

        GoogleTokenInfo info;
        try {
            info = googleClient.get()
                    .uri("/tokeninfo?id_token={t}", idToken)
                    .retrieve()
                    .body(GoogleTokenInfo.class);
        } catch (Exception e) {
            log.warn("google_token_verify_failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Could not verify Google sign-in");
        }

        if (info == null || info.email() == null || info.aud() == null || !clientId.equals(info.aud())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google sign-in");
        }
        if (!"true".equalsIgnoreCase(info.emailVerified())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google email is not verified");
        }

        var email = info.email().trim().toLowerCase();
        var user = userRepository.findByEmail(email)
                .orElseGet(() -> createGoogleUser(email, info.name()));
        return authService.sessionFor(user, false);
    }

    private User createGoogleUser(String email, String name) {
        var localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        var user = User.builder()
                .email(email)
                .name(name != null && !name.isBlank() ? name : localPart)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(Role.USER)
                .build();
        var saved = userRepository.save(user);
        categorySeedService.seedPredefinedForUser(saved);
        return saved;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleTokenInfo(String aud, String email,
                           @JsonProperty("email_verified") String emailVerified,
                           String name) {
    }
}
