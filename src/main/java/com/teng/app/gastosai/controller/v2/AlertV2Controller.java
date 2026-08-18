package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AlertController;
import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link AlertController} on the v2 path.
 *
 * <p>An alert's amounts are already interpolated into {@code message} as display text, so there is
 * no numeric money field here for this version to change.
 */
@RestController
@RequestMapping("/api/v2/alerts")
@RequiredArgsConstructor
public class AlertV2Controller {

	private final AlertController delegate;

	@GetMapping
	@Operation(operationId = "v2ListAlerts")
	public List<AlertResponse> getOrGenerate(@RequestParam(required = false) String month,
			@AuthenticationPrincipal User user) {
		return delegate.getOrGenerate(month, user);
	}

	@PatchMapping("/{id}/read")
	@Operation(operationId = "v2MarkAlertRead")
	public AlertResponse markRead(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return delegate.markRead(id, user);
	}

	@PatchMapping("/{id}/dismiss")
	@Operation(operationId = "v2DismissAlert")
	public AlertResponse dismiss(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return delegate.dismiss(id, user);
	}

	@DeleteMapping("/{id}")
	@Operation(operationId = "v2DeleteAlert")
	public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return delegate.delete(id, user);
	}
}
