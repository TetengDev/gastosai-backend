package com.teng.app.gastosai;

import com.teng.app.gastosai.service.AuthService;
import com.teng.app.gastosai.service.CategorySeedService;
import com.teng.app.gastosai.service.GoogleAuthService;
import com.teng.app.gastosai.service.RegistrationGuardService;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GoogleAuthServiceTest {

    private GoogleAuthService service(String clientId) {
        return new GoogleAuthService(
                mock(UserRepository.class),
                mock(PasswordEncoder.class),
                mock(AuthService.class),
                mock(CategorySeedService.class),
                mock(RegistrationGuardService.class),
                clientId);
    }

    @Test
    void disabled_whenClientIdBlank() {
        assertThat(service("").isEnabled()).isFalse();
        assertThat(service("   ").isEnabled()).isFalse();
        assertThat(service(null).isEnabled()).isFalse();
    }

    @Test
    void enabled_whenClientIdSet() {
        assertThat(service("abc.apps.googleusercontent.com").isEnabled()).isTrue();
    }

    @Test
    void loginWithIdToken_whenDisabled_throwsServiceUnavailable() {
        assertThatThrownBy(() -> service("").loginWithIdToken("any-token", "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not configured");
    }
}
