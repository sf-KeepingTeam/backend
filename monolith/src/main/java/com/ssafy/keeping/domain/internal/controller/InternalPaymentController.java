package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.PaymentCheckResponse;
import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import com.ssafy.keeping.domain.internal.service.InternalPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API - 결제 상태 확인용
 * QR Service의 복구 로직에서 사용
 */
@Slf4j
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final InternalPaymentService internalPaymentService;

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

    /**
     * 결제 상태 확인 - 멱등성 키로 기존 결제 존재 여부 확인
     */
    @GetMapping("/check")
    public ResponseEntity<PaymentCheckResponse> checkPayment(
            @RequestParam String idempotencyKey,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        PaymentCheckResponse response = internalPaymentService.checkPayment(idempotencyKey);
        return ResponseEntity.ok(response);
    }

    private void validateInternalAuth(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new InternalApiAuthException("Internal API 인증 실패");
        }
    }
}
