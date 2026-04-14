package com.ssafy.keeping.domain.auth.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/loadtest")
@ConditionalOnProperty(name = "loadtest.backdoor.enabled", havingValue = "true")
public class LoadTestController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "LoadTest backdoor is enabled"
        ));
    }

    @GetMapping("/verify-customer")
    public ResponseEntity<Map<String, Object>> verifyCustomer(
            @AuthenticationPrincipal Long customerId
    ) {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "role", "CUSTOMER",
                "userId", customerId
        ));
    }

    @GetMapping("/verify-owner")
    public ResponseEntity<Map<String, Object>> verifyOwner(
            @AuthenticationPrincipal Long ownerId
    ) {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "role", "OWNER",
                "userId", ownerId
        ));
    }
}
