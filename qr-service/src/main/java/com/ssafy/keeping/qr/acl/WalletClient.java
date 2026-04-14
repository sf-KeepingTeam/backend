package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.FundsCaptureRequest;
import com.ssafy.keeping.qr.acl.dto.FundsResponse;
import com.ssafy.keeping.qr.acl.dto.PaymentCheckResponse;
import com.ssafy.keeping.qr.acl.dto.RefundRequest;
import com.ssafy.keeping.qr.acl.dto.RefundResponse;
import com.ssafy.keeping.qr.acl.dto.WalletBalanceResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Wallet 서비스를 HTTP로 호출
 *
 * RestTemplate 용도별 분리:
 * - restTemplate: 읽기 작업 (5초 타임아웃)
 * - writeRestTemplate: 쓰기 작업 (3초 Fail-Fast)
 * - recoveryRestTemplate: 복구 작업 (10초, 트랜잭션 외부 호출)
 */
@Slf4j
@Component
public class WalletClient {

    private final RestTemplate restTemplate;
    private final RestTemplate writeRestTemplate;
    private final RestTemplate recoveryRestTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    public WalletClient(
            RestTemplate restTemplate,
            @Qualifier("writeRestTemplate") RestTemplate writeRestTemplate,
            @Qualifier("recoveryRestTemplate") RestTemplate recoveryRestTemplate) {
        this.restTemplate = restTemplate;
        this.writeRestTemplate = writeRestTemplate;
        this.recoveryRestTemplate = recoveryRestTemplate;
    }

    /**
     * 지갑 잔액 조회 (매장별) - 읽기 전용이므로 재시도 가능
     */
    @CircuitBreaker(name = "walletClient", fallbackMethod = "getBalanceFallback")
    @Retry(name = "walletClientReadOnly", fallbackMethod = "getBalanceFallback")
    public BigDecimal getBalance(Long walletId, Long storeId) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/stores/" + storeId + "/balance";

        HttpHeaders headers = createHeaders();

        ResponseEntity<WalletBalanceResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                WalletBalanceResponse.class
        );

        if (response.getBody() != null) {
            return response.getBody().getBalance();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getBalanceFallback(Long walletId, Long storeId, Throwable t) {
        log.error("Wallet 잔액 조회 Fallback 호출: walletId={}, storeId={}, error={}",
                walletId, storeId, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감 + 거래 내역 생성)
     * - writeRestTemplate 사용 (3초 Fail-Fast)
     * - 재시도 없음 (maxAttempts: 1)
     * - 멱등성 키를 통해 네트워크 재시도 시 중복 결제 방지
     */
    @CircuitBreaker(name = "walletClient", fallbackMethod = "captureFallback")
    @Retry(name = "walletClient", fallbackMethod = "captureFallback")
    public FundsResponse capture(FundsCaptureRequest request, String idempotencyKey) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId()
                + "/stores/" + request.getStoreId() + "/capture";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Idempotency-Key", idempotencyKey);

        ResponseEntity<FundsResponse> response = writeRestTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                FundsResponse.class
        );

        log.info("자금 캡처 완료: walletId={}, storeId={}, amount={}, idempotencyKey={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey);

        return response.getBody();
    }

    private FundsResponse captureFallback(FundsCaptureRequest request, String idempotencyKey, Throwable t) {
        log.error("자금 캡처 Fallback 호출: walletId={}, storeId={}, amount={}, idempotencyKey={}, error={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    /**
     * 자금 복원 (결제 취소 시)
     * - writeRestTemplate 사용 (3초 Fail-Fast)
     * - 재시도 없음 (maxAttempts: 1)
     */
    @CircuitBreaker(name = "walletClient", fallbackMethod = "restoreFallback")
    @Retry(name = "walletClient", fallbackMethod = "restoreFallback")
    public void restore(Long walletId, Long storeId, Long amount) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/stores/" + storeId + "/restore";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");

        RestoreRequest body = new RestoreRequest(amount);

        writeRestTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Void.class
        );

        log.info("잔액 복원 완료: walletId={}, storeId={}, amount={}", walletId, storeId, amount);
    }

    private void restoreFallback(Long walletId, Long storeId, Long amount, Throwable t) {
        log.error("잔액 복원 Fallback 호출: walletId={}, storeId={}, amount={}, error={}",
                walletId, storeId, amount, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    /**
     * 결제 상태 확인 - 멱등성 키로 기존 결제 존재 여부 확인
     */
    @CircuitBreaker(name = "walletClient", fallbackMethod = "checkPaymentFallback")
    @Retry(name = "walletClientReadOnly", fallbackMethod = "checkPaymentFallback")
    public PaymentCheckResponse checkPayment(String idempotencyKey) {
        String url = monolithUrl + "/internal/payments/check?idempotencyKey=" + idempotencyKey;

        HttpHeaders headers = createHeaders();

        ResponseEntity<PaymentCheckResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                PaymentCheckResponse.class
        );

        return response.getBody();
    }

    private PaymentCheckResponse checkPaymentFallback(String idempotencyKey, Throwable t) {
        log.error("결제 상태 확인 Fallback 호출: idempotencyKey={}, error={}",
                idempotencyKey, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    /**
     * 환불 처리 - 기존 결제에 대한 환불
     * - writeRestTemplate 사용 (3초 Fail-Fast)
     * - 재시도 없음 (maxAttempts: 1)
     */
    @CircuitBreaker(name = "walletClient", fallbackMethod = "refundFallback")
    @Retry(name = "walletClient", fallbackMethod = "refundFallback")
    public RefundResponse refund(RefundRequest request, String idempotencyKey) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId() + "/refund";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Idempotency-Key", idempotencyKey);

        ResponseEntity<RefundResponse> response = writeRestTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                RefundResponse.class
        );

        log.info("환불 처리 완료: walletId={}, storeId={}, amount={}, idempotencyKey={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey);

        return response.getBody();
    }

    private RefundResponse refundFallback(RefundRequest request, String idempotencyKey, Throwable t) {
        log.error("환불 처리 Fallback 호출: walletId={}, storeId={}, amount={}, idempotencyKey={}, error={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }

    // ========== 복구용 메서드 (recoveryRestTemplate 사용) ==========

    /**
     * 결제 상태 확인 - 복구용
     * - recoveryRestTemplate 사용 (10초 타임아웃)
     * - 재시도 허용 (maxAttempts: 3)
     * - 트랜잭션 외부에서 호출해야 함
     */
    @CircuitBreaker(name = "walletClientRecovery", fallbackMethod = "checkPaymentForRecoveryFallback")
    @Retry(name = "walletClientRecovery", fallbackMethod = "checkPaymentForRecoveryFallback")
    public PaymentCheckResponse checkPaymentForRecovery(String idempotencyKey) {
        String url = monolithUrl + "/internal/payments/check?idempotencyKey=" + idempotencyKey;

        HttpHeaders headers = createHeaders();

        ResponseEntity<PaymentCheckResponse> response = recoveryRestTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                PaymentCheckResponse.class
        );

        return response.getBody();
    }

    private PaymentCheckResponse checkPaymentForRecoveryFallback(String idempotencyKey, Throwable t) {
        log.error("결제 상태 확인 (복구) Fallback 호출: idempotencyKey={}, error={}",
                idempotencyKey, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    /**
     * 환불 처리 - 복구용
     * - recoveryRestTemplate 사용 (10초 타임아웃)
     * - 재시도 허용 (maxAttempts: 3)
     * - 트랜잭션 외부에서 호출해야 함
     */
    @CircuitBreaker(name = "walletClientRecovery", fallbackMethod = "refundForRecoveryFallback")
    @Retry(name = "walletClientRecovery", fallbackMethod = "refundForRecoveryFallback")
    public RefundResponse refundForRecovery(RefundRequest request, String idempotencyKey) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId() + "/refund";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Idempotency-Key", idempotencyKey);

        ResponseEntity<RefundResponse> response = recoveryRestTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                RefundResponse.class
        );

        log.info("환불 처리 완료 (복구): walletId={}, storeId={}, amount={}, idempotencyKey={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey);

        return response.getBody();
    }

    private RefundResponse refundForRecoveryFallback(RefundRequest request, String idempotencyKey, Throwable t) {
        log.error("환불 처리 (복구) Fallback 호출: walletId={}, storeId={}, amount={}, idempotencyKey={}, error={}",
                request.getWalletId(), request.getStoreId(), request.getAmount(), idempotencyKey, t.getMessage());
        throw new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE, t);
    }

    private record RestoreRequest(Long amount) {}
}
