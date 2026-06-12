package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AlertResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<AlertResponse> getOrGenerate(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User user) {
        String resolvedMonth = (month != null && !month.isBlank())
                ? month
                : YearMonth.now().toString();
        return alertService.getOrGenerate(user, resolvedMonth);
    }

    @PatchMapping("/{id}/read")
    public AlertResponse markRead(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return alertService.markRead(id, user);
    }

    @PatchMapping("/{id}/dismiss")
    public AlertResponse dismiss(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return alertService.dismiss(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        alertService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
