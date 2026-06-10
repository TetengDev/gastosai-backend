package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.UserProfileRequest;
import com.teng.app.gastosai.dto.UserProfileResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserProfileService userProfileService;

    private User user() {
        return User.builder().email("a@b.com").name("Alice").nickname("ali").avatarColor("blue").password("x").build();
    }

    @Test
    void getProfile_returnsResponse() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user()));

        UserProfileResponse r = userProfileService.getProfile("a@b.com");

        assertThat(r.email()).isEqualTo("a@b.com");
        assertThat(r.name()).isEqualTo("Alice");
    }

    @Test
    void getProfile_notFound_throws() {
        when(userRepository.findByEmail("x@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getProfile("x@b.com"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateProfile_sameEmail_updatesNameAndNickname() {
        User u = user();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserProfileResponse r = userProfileService.updateProfile("a@b.com",
                new UserProfileRequest("Bob", "bobby", "a@b.com", "red"));

        assertThat(r.name()).isEqualTo("Bob");
    }

    @Test
    void updateProfile_newEmail_noConflict_updatesEmail() {
        User u = user();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.existsByEmail("new@b.com")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserProfileResponse r = userProfileService.updateProfile("a@b.com",
                new UserProfileRequest("Alice", null, "new@b.com", null));

        assertThat(r.email()).isEqualTo("new@b.com");
    }

    @Test
    void updateProfile_newEmail_conflict_throws() {
        User u = user();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(userRepository.existsByEmail("taken@b.com")).thenReturn(true);

        assertThatThrownBy(() -> userProfileService.updateProfile("a@b.com",
                new UserProfileRequest("Alice", null, "taken@b.com", null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
