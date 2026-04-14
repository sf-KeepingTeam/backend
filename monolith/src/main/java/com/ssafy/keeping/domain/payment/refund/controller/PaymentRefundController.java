package com.ssafy.keeping.domain.payment.refund.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.payment.refund.dto.RefundResponse;
import com.ssafy.keeping.domain.payment.refund.service.PaymentRefundService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 전액 결제 취소(가맹점용)
 * - URL: POST /stores/{storeId}/transactions/{transactionId}/refund
 * - 정책: 항상 FULL CANCEL
 * - 멱등: Idempotency-Key 헤더 필수
 * - 결과: transactions에 CANCEL_USE 생성, balances/lot 복원
 */
@RestController
@RequestMapping("/stores/{storeId}/transactions")
@RequiredArgsConstructor
@Validated
public class PaymentRefundController {

    private final PaymentRefundService refundService;

    @PostMapping("/{transactionId}/refund")
    public ResponseEntity<ApiResponse<RefundResponse>> refund(
            @PathVariable Long storeId,
            @PathVariable Long transactionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long ownerId = principal.id();
        // 0) 멱등키 필수
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        // 1) 서비스 호출 (전액 취소 + 멱등 처리)
        IdempotentResult<RefundResponse> res =
                refundService.fullCancel(storeId, transactionId, idempotencyKey, ownerId);

        // 2) 메시지 분기
        String message = res.isReplay()
                ? "이전에 처리된 요청의 결과입니다."
                : (res.getHttpStatus() == HttpStatus.CREATED ? "결제가 취소되었습니다." : "요청이 접수되었습니다.");

        // 3) 202(처리중)인 경우 Retry-After 헤더 세팅
        if (res.getHttpStatus() == HttpStatus.ACCEPTED && res.getRetryAfterSeconds() != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(res.getRetryAfterSeconds()));
            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .headers(headers)
                    .body(ApiResponse.success(message, HttpStatus.ACCEPTED.value(), res.getBody()));
        }

        // 4) 200/201 등 일반 케이스
        return ResponseEntity
                .status(res.getHttpStatus())
                .body(ApiResponse.success(message, res.getHttpStatus().value(), res.getBody()));
    }
}