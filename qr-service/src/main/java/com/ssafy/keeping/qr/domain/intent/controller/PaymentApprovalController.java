package com.ssafy.keeping.qr.domain.intent.controller;

import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.intent.dto.ApproveRequest;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentDetailResponse;
import com.ssafy.keeping.qr.domain.intent.service.PaymentIntentService;
import com.ssafy.keeping.qr.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentApprovalController {

    private final PaymentIntentService paymentIntentService;

    /**
     * 결제 승인
     * - Idempotency-Key 필수
     */
    @PostMapping("/{intentId}/approve")
    public ResponseEntity<ApiResponse<PaymentIntentDetailResponse>> approve(
            @PathVariable("intentId") UUID intentId,
            @RequestHeader("Idempotency-Key") String idemKey,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ApproveRequest body
    ) {
        // Idempotency-Key 검증은 Service에서 수행 (required=true이므로 null은 Spring이 처리)
        IdempotentResult<PaymentIntentDetailResponse> res =
                paymentIntentService.approve(intentId, idemKey, principal.id(), body);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.getHttpStatus());
        if (res.getRetryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(res.getRetryAfterSeconds()));
        }

        String msg = res.isReplay()
                ? "이전에 처리된 요청의 결과입니다."
                : (res.getHttpStatus().is2xxSuccessful()
                ? "결제가 승인되었습니다."
                : "요청 처리에 실패했습니다.");

        return builder.body(ApiResponse.success(msg, res.getHttpStatus().value(), res.getBody()));
    }
}
