package com.ssafy.keeping.domain.internal.webhook;

import com.ssafy.keeping.domain.internal.dto.MenuResponse;
import com.ssafy.keeping.domain.internal.dto.StoreResponse;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.store.model.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * QR Service로 캐시 갱신 Webhook 전송
 * Fire-and-forget 패턴: 실패해도 예외 무시
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrServiceWebhookPublisher {

    private final RestTemplate restTemplate;

    @Value("${qr-service.url:http://localhost:8082}")
    private String qrServiceUrl;

    @Value("${qr-service.webhook.enabled:true}")
    private boolean webhookEnabled;

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

    /**
     * Store 생성/수정 시 캐시 갱신 Push
     * 실패 시 3회 재시도 (500ms → 1s → 2s)
     */
    @Async("webhookExecutor")
    @Retryable(
            retryFor = {RestClientException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void publishStoreUpdate(Store store) {
        if (!webhookEnabled) {
            log.debug("Webhook 비활성화됨, Store 캐시 Push 스킵: storeId={}", store.getStoreId());
            return;
        }

        String url = qrServiceUrl + "/internal/cache/stores/" + store.getStoreId();
        StoreResponse payload = StoreResponse.from(store);

        HttpHeaders headers = createHeaders();
        HttpEntity<StoreResponse> request = new HttpEntity<>(payload, headers);

        restTemplate.postForEntity(url, request, Void.class);
        log.info("Store 캐시 Push 완료: storeId={}", store.getStoreId());
    }

    @Recover
    public void recoverStoreUpdate(Exception e, Store store) {
        log.error("Store 캐시 Push 최종 실패 (3회 재시도 후): storeId={}, error={}",
                store.getStoreId(), e.getMessage());
    }

    /**
     * Store 삭제 시 캐시 삭제 Push
     * 실패 시 3회 재시도 (500ms → 1s → 2s)
     */
    @Async("webhookExecutor")
    @Retryable(
            retryFor = {RestClientException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void publishStoreDelete(Long storeId) {
        if (!webhookEnabled) {
            log.debug("Webhook 비활성화됨, Store 캐시 삭제 Push 스킵: storeId={}", storeId);
            return;
        }

        String url = qrServiceUrl + "/internal/cache/stores/" + storeId;

        HttpHeaders headers = createHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.postForEntity(url, request, Void.class);
        log.info("Store 캐시 삭제 Push 완료: storeId={}", storeId);
    }

    @Recover
    public void recoverStoreDelete(Exception e, Long storeId) {
        log.error("Store 캐시 삭제 Push 최종 실패 (3회 재시도 후): storeId={}, error={}",
                storeId, e.getMessage());
    }

    /**
     * Menu 생성/수정 시 캐시 갱신 Push
     * 실패 시 3회 재시도 (500ms → 1s → 2s)
     */
    @Async("webhookExecutor")
    @Retryable(
            retryFor = {RestClientException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void publishMenuUpdate(Menu menu) {
        if (!webhookEnabled) {
            log.debug("Webhook 비활성화됨, Menu 캐시 Push 스킵: menuId={}", menu.getMenuId());
            return;
        }

        String url = qrServiceUrl + "/internal/cache/menus/" + menu.getMenuId();
        MenuResponse payload = MenuResponse.from(menu);

        HttpHeaders headers = createHeaders();
        HttpEntity<MenuResponse> request = new HttpEntity<>(payload, headers);

        restTemplate.postForEntity(url, request, Void.class);
        log.info("Menu 캐시 Push 완료: menuId={}", menu.getMenuId());
    }

    @Recover
    public void recoverMenuUpdate(Exception e, Menu menu) {
        log.error("Menu 캐시 Push 최종 실패 (3회 재시도 후): menuId={}, error={}",
                menu.getMenuId(), e.getMessage());
    }

    /**
     * Menu 삭제 시 캐시 삭제 Push
     * 실패 시 3회 재시도 (500ms → 1s → 2s)
     */
    @Async("webhookExecutor")
    @Retryable(
            retryFor = {RestClientException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void publishMenuDelete(Long menuId) {
        if (!webhookEnabled) {
            log.debug("Webhook 비활성화됨, Menu 캐시 삭제 Push 스킵: menuId={}", menuId);
            return;
        }

        String url = qrServiceUrl + "/internal/cache/menus/" + menuId;

        HttpHeaders headers = createHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.postForEntity(url, request, Void.class);
        log.info("Menu 캐시 삭제 Push 완료: menuId={}", menuId);
    }

    @Recover
    public void recoverMenuDelete(Exception e, Long menuId) {
        log.error("Menu 캐시 삭제 Push 최종 실패 (3회 재시도 후): menuId={}, error={}",
                menuId, e.getMessage());
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
