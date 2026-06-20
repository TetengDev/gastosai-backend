package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.entity.MagicLinkToken;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.MagicLinkTokenRepository;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkService {

    private static final int ABUSE_LIMIT = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MagicLinkTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final AuthService authService;

    @Value("${gastos.magiclink.ttl-minutes:15}")
    private int ttlMinutes;

    @Value("${gastos.app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @Transactional
    public void requestLink(String email) {
        var normalised = email.trim().toLowerCase();

        if (normalised.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            log.warn("magic_link_rejected_control_chars");
            return;
        }

        if (tokenRepository.countUnusedSince(normalised, LocalDateTime.now().minusMinutes(ttlMinutes)) >= ABUSE_LIMIT) {
            log.warn("magic_link_rate_limited");
            return;
        }

        var user = userRepository.findByEmail(normalised)
                .orElseGet(() -> createPasswordlessUser(normalised));

        var rawToken = generateRawToken();
        var hash = sha256Hex(rawToken);

        tokenRepository.save(MagicLinkToken.builder()
                .email(normalised)
                .tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusMinutes(ttlMinutes))
                .build());

        var link = frontendBaseUrl + "/auth/verify?token=" + rawToken;
        emailSender.sendMagicLink(user.getEmail(), link);
    }

    @Transactional
    public AuthResponse verify(String rawToken) {
        var hash = sha256Hex(rawToken);
        var token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired link"));

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired link");
        }

        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);

        var user = userRepository.findByEmail(token.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired link"));

        return authService.sessionFor(user, false);
    }

    private User createPasswordlessUser(String email) {
        var localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        var user = User.builder()
                .email(email)
                .name(localPart)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(Role.USER)
                .build();
        return userRepository.save(user);
    }

    private static String generateRawToken() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
