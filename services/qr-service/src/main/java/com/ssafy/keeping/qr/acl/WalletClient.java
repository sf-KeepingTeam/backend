package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.FundsCaptureRequest;
import com.ssafy.keeping.qr.acl.dto.FundsResponse;
import com.ssafy.keeping.qr.acl.dto.WalletBalanceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 지갑 잔액 조회 (매장별)
     */
    public BigDecimal getBalance(Long walletId, Long storeId) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/stores/" + storeId + "/balance";

        try {
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

        } catch (Exception e) {
            log.error("Wallet 서비스 호출 실패: walletId={}, storeId={}, error={}", walletId, storeId, e.getMessage());
            throw new RuntimeException("Wallet 서비스 호출 실패", e);
        }
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감 + 거래 내역 생성)
     */
    public FundsResponse capture(FundsCaptureRequest request) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId()
                + "/stores/" + request.getStoreId() + "/capture";

        try {
            HttpHeaders headers = createHeaders();
            headers.set("Content-Type", "application/json");

            ResponseEntity<FundsResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    FundsResponse.class
            );

            log.info("자금 캡처 완료: walletId={}, storeId={}, amount={}",
                    request.getWalletId(), request.getStoreId(), request.getAmount());

            return response.getBody();

        } catch (Exception e) {
            log.error("자금 캡처 실패: walletId={}, storeId={}, amount={}, error={}",
                    request.getWalletId(), request.getStoreId(), request.getAmount(), e.getMessage());
            throw new RuntimeException("자금 캡처 실패", e);
        }
    }

    /**
     * 자금 복원 (결제 취소 시)
     */
    public void restore(Long walletId, Long storeId, Long amount) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/stores/" + storeId + "/restore";

        try {
            HttpHeaders headers = createHeaders();
            headers.set("Content-Type", "application/json");

            RestoreRequest body = new RestoreRequest(amount);

            restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Void.class
            );

            log.info("잔액 복원 완료: walletId={}, storeId={}, amount={}", walletId, storeId, amount);

        } catch (Exception e) {
            log.error("잔액 복원 실패: walletId={}, storeId={}, amount={}, error={}",
                    walletId, storeId, amount, e.getMessage());
            throw new RuntimeException("잔액 복원 실패", e);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }

    private record RestoreRequest(Long amount) {}
}
