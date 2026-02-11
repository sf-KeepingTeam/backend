package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.CustomerResponse;
import com.ssafy.keeping.qr.acl.dto.PinVerifyRequest;
import com.ssafy.keeping.qr.acl.dto.PinVerifyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Customer 서비스를 HTTP로 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 고객 정보 조회
     */
    public Optional<CustomerResponse> getCustomer(Long customerId) {
        String url = monolithUrl + "/internal/customers/" + customerId;

        try {
            HttpHeaders headers = createHeaders();

            ResponseEntity<CustomerResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    CustomerResponse.class
            );

            return Optional.ofNullable(response.getBody());

        } catch (Exception e) {
            log.error("Customer 서비스 호출 실패: customerId={}, error={}", customerId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * PIN 검증
     */
    public boolean verifyPin(Long customerId, String pin) {
        String url = monolithUrl + "/internal/customers/" + customerId + "/pin-verify";

        try {
            HttpHeaders headers = createHeaders();
            headers.set("Content-Type", "application/json");

            PinVerifyRequest body = new PinVerifyRequest(pin);

            ResponseEntity<PinVerifyResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    PinVerifyResponse.class
            );

            return response.getBody() != null && response.getBody().isVerified();

        } catch (Exception e) {
            log.error("PIN 검증 실패: customerId={}, error={}", customerId, e.getMessage());
            return false;
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
