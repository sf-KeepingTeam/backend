package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.internal.dto.FundsCaptureRequest;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.RefundRequest;
import com.ssafy.keeping.domain.internal.dto.RefundResponse;
import com.ssafy.keeping.domain.internal.dto.WalletBalanceResponse;
import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import com.ssafy.keeping.domain.internal.service.InternalWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API - 마이크로서비스 간 통신용
 * X-Internal-Auth 헤더로 보호
 */
@Slf4j
@RestController
@RequestMapping("/internal/wallets")
@RequiredArgsConstructor
public class InternalWalletController {

    private final InternalWalletService internalWalletService;

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

    /**
     * 지갑의 매장별 잔액 조회
     */
    @GetMapping("/{walletId}/stores/{storeId}/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        WalletBalanceResponse response = internalWalletService.getBalance(walletId, storeId);
        return ResponseEntity.ok(response);
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감 + 거래 내역 생성)
     * Idempotency-Key 헤더를 통해 중복 결제 방지
     */
    @PostMapping("/{walletId}/stores/{storeId}/capture")
    public ResponseEntity<?> capture(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody FundsCaptureRequest request
    ) {
        validateInternalAuth(authToken);

        IdempotentResult<FundsResponse> result = internalWalletService.captureIdempotent(
                request, idempotencyKey);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.getHttpStatus());
        if (result.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, result.getRetryAfterSeconds().toString());
        }
        return builder.body(result.getBody());
    }

    /**
     * 잔액 복원 (결제 취소 시)
     */
    @PostMapping("/{walletId}/stores/{storeId}/restore")
    public ResponseEntity<Void> restore(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody RestoreRequest request
    ) {
        validateInternalAuth(authToken);

        internalWalletService.restore(walletId, storeId, request.amount());
        return ResponseEntity.ok().build();
    }

    /**
     * 환불 처리 - 기존 결제에 대한 환불
     * Idempotency-Key 헤더를 통해 중복 환불 방지
     */
    @PostMapping("/{walletId}/refund")
    public ResponseEntity<?> refund(
            @PathVariable Long walletId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody RefundRequest request
    ) {
        validateInternalAuth(authToken);

        IdempotentResult<RefundResponse> result = internalWalletService.processRefundIdempotent(
                walletId, request, idempotencyKey);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.getHttpStatus());
        if (result.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, result.getRetryAfterSeconds().toString());
        }
        return builder.body(result.getBody());
    }

    private void validateInternalAuth(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new InternalApiAuthException("Internal API 인증 실패");
        }
    }

    public record RestoreRequest(Long amount) {}
}
