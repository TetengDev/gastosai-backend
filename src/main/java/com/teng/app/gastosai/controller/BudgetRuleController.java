package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.BucketAssignmentRequest;
import com.teng.app.gastosai.dto.BudgetRuleRequest;
import com.teng.app.gastosai.dto.BudgetRuleResponse;
import com.teng.app.gastosai.dto.BudgetRuleSummaryResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.BudgetRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/budget-rules")
@RequiredArgsConstructor
public class BudgetRuleController {

    private final BudgetRuleService budgetRuleService;

    @GetMapping
    public BudgetRuleResponse get(@AuthenticationPrincipal User user) {
        return budgetRuleService.get(user);
    }

    @PutMapping
    public BudgetRuleResponse upsert(@Valid @RequestBody BudgetRuleRequest request,
            @AuthenticationPrincipal User user) {
        return budgetRuleService.upsert(user, request);
    }

    @PutMapping("/buckets")
    public ResponseEntity<Void> assignBuckets(@Valid @RequestBody BucketAssignmentRequest request,
            @AuthenticationPrincipal User user) {
        budgetRuleService.assignBuckets(user, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public BudgetRuleSummaryResponse summary(
            @RequestParam(value = "month", required = false) String month,
            @AuthenticationPrincipal User user) {
        String resolved = (month == null || month.isBlank()) ? YearMonth.now().toString() : month;
        return budgetRuleService.summary(user, resolved);
    }
}
