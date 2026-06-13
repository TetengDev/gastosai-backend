package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.GoalRequest;
import com.teng.app.gastosai.dto.GoalResponse;
import com.teng.app.gastosai.entity.GoalStatus;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SavingsGoalService {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final SavingsGoalRepository savingsGoalRepository;

	@Transactional
	public GoalResponse create(GoalRequest request, User user) {
		SavingsGoal goal = savingsGoalRepository.save(SavingsGoal.builder()
				.user(user)
				.name(request.name().strip())
				.targetAmount(request.targetAmount())
				.savedAmount(request.savedAmount())
				.targetDate(request.targetDate())
				.paused(request.paused())
				.currency(request.currency() != null ? request.currency() : "PHP")
				.build());
		return toResponse(goal);
	}

	@Transactional(readOnly = true)
	public List<GoalResponse> findAll(User user) {
		return savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user).stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public GoalResponse findById(Long id, User user) {
		return savingsGoalRepository.findByIdAndUser(id, user)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Goal not found: " + id));
	}

	@Transactional
	public GoalResponse update(Long id, GoalRequest request, User user) {
		SavingsGoal goal = savingsGoalRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Goal not found: " + id));
		goal.setName(request.name().strip());
		goal.setTargetAmount(request.targetAmount());
		goal.setSavedAmount(request.savedAmount());
		goal.setTargetDate(request.targetDate());
		goal.setPaused(request.paused());
		goal.setCurrency(request.currency() != null ? request.currency() : "PHP");
		return toResponse(savingsGoalRepository.save(goal));
	}

	@Transactional
	public void delete(Long id, User user) {
		SavingsGoal goal = savingsGoalRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Goal not found: " + id));
		savingsGoalRepository.delete(goal);
	}

	private GoalResponse toResponse(SavingsGoal g) {
		BigDecimal target = g.getTargetAmount().setScale(2, RoundingMode.HALF_UP);
		BigDecimal saved = g.getSavedAmount().setScale(2, RoundingMode.HALF_UP);
		BigDecimal progressPercent = target.compareTo(BigDecimal.ZERO) == 0
				? BigDecimal.ZERO
				: saved.divide(target, 4, RoundingMode.HALF_UP)
						.multiply(HUNDRED)
						.min(HUNDRED)
						.setScale(2, RoundingMode.HALF_UP);
		return new GoalResponse(g.getId(), g.getName(), target, saved, progressPercent,
				g.getTargetDate(), deriveStatus(g, saved, target), g.isPaused(), g.getCreatedAt(),
				g.getCurrency());
	}

	private GoalStatus deriveStatus(SavingsGoal g, BigDecimal saved, BigDecimal target) {
		if (g.isPaused()) return GoalStatus.PAUSED;
		if (saved.compareTo(target) >= 0) return GoalStatus.COMPLETED;
		LocalDate targetDate = g.getTargetDate();
		if (targetDate == null) return GoalStatus.ON_TRACK;
		LocalDate today = LocalDate.now();
		if (targetDate.isBefore(today)) return GoalStatus.BEHIND;
		LocalDate startDate = g.getCreatedAt().toLocalDate();
		long daysTotal = Math.max(1, ChronoUnit.DAYS.between(startDate, targetDate));
		long daysElapsed = Math.max(0, Math.min(daysTotal, ChronoUnit.DAYS.between(startDate, today)));
		BigDecimal expectedPct = BigDecimal.valueOf(daysElapsed)
				.divide(BigDecimal.valueOf(daysTotal), 4, RoundingMode.HALF_UP)
				.multiply(HUNDRED);
		BigDecimal actualPct = target.compareTo(BigDecimal.ZERO) == 0
				? HUNDRED
				: saved.divide(target, 4, RoundingMode.HALF_UP).multiply(HUNDRED);
		return actualPct.compareTo(expectedPct) >= 0 ? GoalStatus.ON_TRACK : GoalStatus.BEHIND;
	}
}
