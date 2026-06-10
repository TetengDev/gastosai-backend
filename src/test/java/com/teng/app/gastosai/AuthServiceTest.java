package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.dto.LoginRequest;
import com.teng.app.gastosai.dto.RegisterRequest;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @InjectMocks AuthService authService;

    @Test
    void register_success() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
        when(passwordEncoder.encode("pass")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jwtUtil.generate("a@b.com")).thenReturn("token");

        AuthResponse r = authService.register(new RegisterRequest("Alice", "a@b.com", "pass"));

        assertThat(r.token()).isEqualTo("token");
        assertThat(r.email()).isEqualTo("a@b.com");
    }

    @Test
    void register_duplicateEmail_throws() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Alice", "a@b.com", "pass")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void login_success() {
        User user = User.builder().name("Alice").email("a@b.com").password("hashed").build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hashed")).thenReturn(true);
        when(jwtUtil.generate("a@b.com")).thenReturn("token");

        AuthResponse r = authService.login(new LoginRequest("a@b.com", "pass"));

        assertThat(r.token()).isEqualTo("token");
    }

    @Test
    void login_userNotFound_throws() {
        when(userRepository.findByEmail("x@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("x@b.com", "pass")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void login_wrongPassword_throws() {
        User user = User.builder().name("Alice").email("a@b.com").password("hashed").build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
