package com.ssafy.keeping.qrpayment.acl;

import com.ssafy.keeping.qrpayment.acl.dto.WalletBalanceResponse;
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

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    /**
     * 지갑 잔액 조회
     */
    public BigDecimal getBalance(Long walletId) {
        String url = monolithUrl + "/api/wallets/" + walletId + "/balance";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Auth", INTERNAL_AUTH_TOKEN);

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
            log.error("Wallet 서비스 호출 실패: walletId={}, error={}", walletId, e.getMessage());
            throw new RuntimeException("Wallet 서비스 호출 실패", e);
        }
    }

    /**
     * 지갑에서 금액 차감 (결제 시)
     */
    public void deductBalance(Long walletId, BigDecimal amount) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/deduct";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Auth", INTERNAL_AUTH_TOKEN);
            headers.set("Content-Type", "application/json");

            DeductRequest body = new DeductRequest(amount);

            restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Void.class
            );

            log.info("잔액 차감 완료: walletId={}, amount={}", walletId, amount);

        } catch (Exception e) {
            log.error("잔액 차감 실패: walletId={}, amount={}, error={}", walletId, amount, e.getMessage());
            throw new RuntimeException("잔액 차감 실패", e);
        }
    }

    private record DeductRequest(BigDecimal amount) {}
}
