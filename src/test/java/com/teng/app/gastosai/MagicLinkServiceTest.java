package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.entity.MagicLinkToken;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.MagicLinkTokenRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AuthService;
import com.teng.app.gastosai.service.CategorySeedService;
import com.teng.app.gastosai.service.EmailSender;
import com.teng.app.gastosai.service.MagicLinkService;
import com.teng.app.gastosai.service.RegistrationGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
class MagicLinkServiceTest {

    @Mock MagicLinkTokenRepository tokenRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailSender emailSender;
    @Mock AuthService authService;
    @Mock CategorySeedService categorySeedService;
    @Mock RegistrationGuardService registrationGuardService;
    @InjectMocks MagicLinkService magicLinkService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(magicLinkService, "ttlMinutes", 15);
        ReflectionTestUtils.setField(magicLinkService, "frontendBaseUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(magicLinkService, "magicLinkDailyMax", 200);
    }

    @Test
    void requestLink_knownEmail_savesTokenAndSendsEmail() {
        var user = User.builder().email("alice@example.com").name("alice").role(Role.USER).password("hashed").build();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.countUnusedSince(anyString(), any())).thenReturn(0L);
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        magicLinkService.requestLink("alice@example.com", "1.2.3.4");

        var captor = ArgumentCaptor.forClass(MagicLinkToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(captor.getValue().getTokenHash()).hasSize(64);

        verify(emailSender).sendMagicLink(anyString(), anyString());
    }

    @Test
    void requestLink_unknownEmail_autoCreatesUserAndSendsEmail() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(tokenRepository.countUnusedSince(anyString(), any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        magicLinkService.requestLink("new@example.com", "1.2.3.4");

        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(userCaptor.getValue().getName()).isEqualTo("new");

        verify(emailSender).sendMagicLink(anyString(), anyString());
    }

    @Test
    void requestLink_normalisesEmail() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(tokenRepository.countUnusedSince(anyString(), any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        magicLinkService.requestLink("  ALICE@EXAMPLE.COM  ", "1.2.3.4");

        var captor = ArgumentCaptor.forClass(MagicLinkToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void requestLink_abovAbuseLimit_skipsEmail() {
        when(tokenRepository.countUnusedSince(anyString(), any())).thenReturn(5L);

        magicLinkService.requestLink("abuser@example.com", "1.2.3.4");

        verify(emailSender, never()).sendMagicLink(anyString(), anyString());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void verify_validToken_returnsAuthResponseAndMarksUsed() {
        var user = User.builder().email("alice@example.com").name("alice").role(Role.USER).password("hashed").build();
        var rawToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=";
        var token = MagicLinkToken.builder()
                .email("alice@example.com")
                .tokenHash(sha256Hex(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(tokenRepository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        var expected = new AuthResponse("jwt", "alice@example.com", "alice", null, null, null, null, false, "USER");
        when(authService.sessionFor(user, false)).thenReturn(expected);

        var result = magicLinkService.verify(rawToken);

        assertThat(result).isEqualTo(expected);
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void verify_unknownToken_throws401() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> magicLinkService.verify("badtoken"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(UNAUTHORIZED));
    }

    @Test
    void verify_expiredToken_throws401() {
        var token = MagicLinkToken.builder()
                .email("alice@example.com")
                .tokenHash("anything")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> magicLinkService.verify("sometoken"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(UNAUTHORIZED));
    }

    @Test
    void verify_alreadyUsedToken_throws401() {
        var token = MagicLinkToken.builder()
                .email("alice@example.com")
                .tokenHash("anything")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .usedAt(LocalDateTime.now().minusSeconds(30))
                .build();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> magicLinkService.verify("sometoken"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(UNAUTHORIZED));
    }

    @Test
    void verify_singleUse_secondVerifyFails() {
        var rawToken = "validtoken123abc";
        var token = MagicLinkToken.builder()
                .email("alice@example.com")
                .tokenHash(sha256Hex(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        var user = User.builder().email("alice@example.com").name("alice").role(Role.USER).password("hashed").build();

        when(tokenRepository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));
        when(tokenRepository.save(any())).thenAnswer(i -> {
            var saved = (MagicLinkToken) i.getArgument(0);
            return saved;
        });
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(authService.sessionFor(any(), any(Boolean.class))).thenReturn(
                new AuthResponse("jwt", "alice@example.com", "alice", null, null, null, null, false, "USER"));

        magicLinkService.verify(rawToken);
        assertThat(token.getUsedAt()).isNotNull();

        assertThatThrownBy(() -> magicLinkService.verify(rawToken))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(UNAUTHORIZED));
    }

    @Test
    void requestLink_dailyBudgetExceeded_skipsEmail() {
        ReflectionTestUtils.setField(magicLinkService, "magicLinkDailyMax", 1);

        var user = User.builder().email("a@example.com").name("a").role(Role.USER).password("hashed").build();
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.countUnusedSince(anyString(), any())).thenReturn(0L);
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        magicLinkService.requestLink("a@example.com", "1.2.3.4");

        verify(emailSender).sendMagicLink(anyString(), anyString());

        when(userRepository.findByEmail("b@example.com")).thenReturn(Optional.of(
                User.builder().email("b@example.com").name("b").role(Role.USER).password("hashed").build()));
        when(tokenRepository.countUnusedSince(eq("b@example.com"), any())).thenReturn(0L);

        magicLinkService.requestLink("b@example.com", "1.2.3.4");

        verify(emailSender).sendMagicLink(anyString(), anyString());
    }

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
