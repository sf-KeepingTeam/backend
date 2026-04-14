package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.NotificationRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Notification 서비스를 HTTP로 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 고객에게 알림 전송
     */
    @CircuitBreaker(name = "notificationClient", fallbackMethod = "sendToCustomerFallback")
    @Retry(name = "notificationClient", fallbackMethod = "sendToCustomerFallback")
    public void sendToCustomer(Long customerId, String type, String content) {
        send("CUSTOMER", customerId, type, content);
    }

    private void sendToCustomerFallback(Long customerId, String type, String content, Throwable t) {
        log.warn("Notification 전송 Fallback (Customer): customerId={}, type={}, error={}",
                customerId, type, t.getMessage());
        // 알림 실패는 비즈니스 로직에 영향을 주지 않음 - 무시
    }

    /**
     * 점주에게 알림 전송
     */
    @CircuitBreaker(name = "notificationClient", fallbackMethod = "sendToOwnerFallback")
    @Retry(name = "notificationClient", fallbackMethod = "sendToOwnerFallback")
    public void sendToOwner(Long ownerId, String type, String content) {
        send("OWNER", ownerId, type, content);
    }

    private void sendToOwnerFallback(Long ownerId, String type, String content, Throwable t) {
        log.warn("Notification 전송 Fallback (Owner): ownerId={}, type={}, error={}",
                ownerId, type, t.getMessage());
        // 알림 실패는 비즈니스 로직에 영향을 주지 않음 - 무시
    }

    private void send(String targetType, Long targetId, String type, String content) {
        String url = monolithUrl + "/internal/notifications/send";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");

        NotificationRequest body = NotificationRequest.builder()
                .targetType(targetType)
                .targetId(targetId)
                .type(type)
                .content(content)
                .build();

        restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Void.class
        );

        log.info("알림 전송 완료: targetType={}, targetId={}, type={}", targetType, targetId, type);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
