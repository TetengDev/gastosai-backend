package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.entity.GoalStatus;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.service.SavingsGoalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SavingsGoalServiceTest {

    @Mock SavingsGoalRepository savingsGoalRepository;

    @InjectMocks SavingsGoalService savingsGoalService;

    private User user() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
    }

    private SavingsGoal goal(BigDecimal target, BigDecimal saved, LocalDate targetDate, boolean paused) {
        return SavingsGoal.builder()
                .id(1L)
                .user(user())
                .name("Emergency Fund")
                .targetAmount(target)
                .savedAmount(saved)
                .targetDate(targetDate)
                .paused(paused)
                .currency("PHP")
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();
    }

    // --- create ---

    @Test
    void create_savesAndReturnsResponse() {
        User u = user();
        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("50000.00"),
                new BigDecimal("10000.00"), LocalDate.now().plusMonths(6), false, "PHP");

        SavingsGoal saved = goal(new BigDecimal("50000.00"), new BigDecimal("10000.00"),
                LocalDate.now().plusMonths(6), false);
        when(savingsGoalRepository.save(any())).thenReturn(saved);

        GoalResponse result = savingsGoalService.create(req, u);

        assertThat(result.name()).isEqualTo("Emergency Fund");
        assertThat(result.targetAmount()).isEqualByComparingTo("50000.00");
        assertThat(result.savedAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void create_defaultsCurrencyToPhp_whenNull() {
        User u = user();
        GoalRequest req = new GoalRequest("Vacation", new BigDecimal("20000.00"),
                BigDecimal.ZERO, null, false, null);

        SavingsGoal saved = goal(new BigDecimal("20000.00"), BigDecimal.ZERO, null, false);
        when(savingsGoalRepository.save(any())).thenReturn(saved);

        savingsGoalService.create(req, u);

        verify(savingsGoalRepository).save(argThat(g -> "PHP".equals(g.getCurrency())));
    }

    // --- findAll ---

    @Test
    void findAll_returnsListForUser() {
        User u = user();
        SavingsGoal g = goal(new BigDecimal("10000"), new BigDecimal("5000"), null, false);
        when(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(u)).thenReturn(List.of(g));

        List<GoalResponse> result = savingsGoalService.findAll(u);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Emergency Fund");
    }

    // --- findById ---

    @Test
    void findById_notFound_throws() {
        User u = user();
        when(savingsGoalRepository.findByIdAndUser(99L, u)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savingsGoalService.findById(99L, u))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Goal not found");
    }

    // --- update ---

    @Test
    void update_notFound_throws() {
        User u = user();
        GoalRequest req = new GoalRequest("X", BigDecimal.TEN, BigDecimal.ONE, null, false, "PHP");
        when(savingsGoalRepository.findByIdAndUser(99L, u)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savingsGoalService.update(99L, req, u))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- delete ---

    @Test
    void delete_deletesGoal() {
        User u = user();
        SavingsGoal g = goal(BigDecimal.TEN, BigDecimal.ONE, null, false);
        when(savingsGoalRepository.findByIdAndUser(1L, u)).thenReturn(Optional.of(g));

        savingsGoalService.delete(1L, u);

        verify(savingsGoalRepository).delete(g);
    }

    @Test
    void delete_notFound_throws() {
        User u = user();
        when(savingsGoalRepository.findByIdAndUser(99L, u)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savingsGoalService.delete(99L, u))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- status derivation ---

    @Test
    void status_paused_returnsPaused() {
        User u = user();
        SavingsGoal g = goal(new BigDecimal("10000"), new BigDecimal("5000"), null, true);
        when(savingsGoalRepository.findByIdAndUser(1L, u)).thenReturn(Optional.of(g));
        when(savingsGoalRepository.save(any())).thenReturn(g);

        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("10000"),
                new BigDecimal("5000"), null, true, "PHP");
        GoalResponse result = savingsGoalService.update(1L, req, u);

        assertThat(result.status()).isEqualTo(GoalStatus.PAUSED);
    }

    @Test
    void status_completed_whenSavedGeTarget() {
        SavingsGoal g = goal(new BigDecimal("10000"), new BigDecimal("10000"), null, false);
        User u = user();
        when(savingsGoalRepository.findByIdAndUser(1L, u)).thenReturn(Optional.of(g));
        when(savingsGoalRepository.save(any())).thenReturn(g);

        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("10000"),
                new BigDecimal("10000"), null, false, "PHP");
        GoalResponse result = savingsGoalService.update(1L, req, u);

        assertThat(result.status()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    void status_behind_whenTargetDatePassed() {
        LocalDate pastDate = LocalDate.now().minusDays(10);
        SavingsGoal g = goal(new BigDecimal("10000"), new BigDecimal("2000"), pastDate, false);
        User u = user();
        when(savingsGoalRepository.findByIdAndUser(1L, u)).thenReturn(Optional.of(g));
        when(savingsGoalRepository.save(any())).thenReturn(g);

        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("10000"),
                new BigDecimal("2000"), pastDate, false, "PHP");
        GoalResponse result = savingsGoalService.update(1L, req, u);

        assertThat(result.status()).isEqualTo(GoalStatus.BEHIND);
    }

    @Test
    void status_onTrack_whenNoTargetDate() {
        SavingsGoal g = goal(new BigDecimal("10000"), new BigDecimal("1000"), null, false);
        User u = user();
        when(savingsGoalRepository.findByIdAndUser(1L, u)).thenReturn(Optional.of(g));
        when(savingsGoalRepository.save(any())).thenReturn(g);

        GoalRequest req = new GoalRequest("Emergency Fund", new BigDecimal("10000"),
                new BigDecimal("1000"), null, false, "PHP");
        GoalResponse result = savingsGoalService.update(1L, req, u);

        assertThat(result.status()).isEqualTo(GoalStatus.ON_TRACK);
    }

    @Test
    void progressPercent_cappedAt100() {
        SavingsGoal g = goal(new BigDecimal("1000"), new BigDecimal("2000"), null, false);
        when(savingsGoalRepository.save(any())).thenReturn(g);

        GoalRequest req = new GoalRequest("X", new BigDecimal("1000"),
                new BigDecimal("2000"), null, false, "PHP");
        GoalResponse result = savingsGoalService.create(req, user());

        assertThat(result.progressPercent()).isEqualByComparingTo("100.00");
    }
}
