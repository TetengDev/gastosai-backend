package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.EntitlementResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    @GetMapping("/entitlements")
    public EntitlementResponse entitlements(@AuthenticationPrincipal User user) {
        EntitlementService.Entitlements entitlements = entitlementService.describe(user);
        return new EntitlementResponse(entitlements.plan(), entitlements.status(), entitlements.features());
    }
}
