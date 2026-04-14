package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.CustomerResponse;
import com.ssafy.keeping.qr.acl.dto.PinVerifyRequest;
import com.ssafy.keeping.qr.acl.dto.PinVerifyResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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
    @CircuitBreaker(name = "customerClient", fallbackMethod = "getCustomerFallback")
    @Retry(name = "customerClient", fallbackMethod = "getCustomerFallback")
    public Optional<CustomerResponse> getCustomer(Long customerId) {
        String url = monolithUrl + "/internal/customers/" + customerId;

        HttpHeaders headers = createHeaders();

        ResponseEntity<CustomerResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                CustomerResponse.class
        );

        return Optional.ofNullable(response.getBody());
    }

    private Optional<CustomerResponse> getCustomerFallback(Long customerId, Throwable t) {
        log.error("Customer 서비스 Fallback 호출: customerId={}, error={}", customerId, t.getMessage());
        throw new CustomException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE, t);
    }

    /**
     * PIN 검증
     */
    @CircuitBreaker(name = "customerClient", fallbackMethod = "verifyPinFallback")
    @Retry(name = "customerClient", fallbackMethod = "verifyPinFallback")
    public boolean verifyPin(Long customerId, String pin) {
        String url = monolithUrl + "/internal/customers/" + customerId + "/pin-verify";

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
    }

    private boolean verifyPinFallback(Long customerId, String pin, Throwable t) {
        log.error("PIN 검증 Fallback 호출: customerId={}, error={}", customerId, t.getMessage());
        throw new CustomException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE, t);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
