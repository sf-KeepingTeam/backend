package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.FundsCaptureRequest;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.WalletBalanceResponse;
import com.ssafy.keeping.domain.internal.service.InternalWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

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
     */
    @PostMapping("/{walletId}/stores/{storeId}/capture")
    public ResponseEntity<FundsResponse> capture(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody FundsCaptureRequest request
    ) {
        validateInternalAuth(authToken);

        FundsResponse response = internalWalletService.capture(request);
        return ResponseEntity.ok(response);
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

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }

    public record RestoreRequest(Long amount) {}
}
