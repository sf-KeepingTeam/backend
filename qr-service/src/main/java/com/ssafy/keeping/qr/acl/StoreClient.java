package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.cache.StoreCacheRepository;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.config.CacheModeConfig;
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
 * 모놀리스의 Store 서비스를 HTTP로 호출
 *
 * 캐시 모드별 동작:
 * - NONE: 캐시 미사용, 항상 모놀리스 직접 호출
 * - PULL: Cache-Aside (캐시 미스 시 조회 후 저장)
 * - PUSH: Webhook Push + Cache-Aside Fallback
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreClient {

    private final RestTemplate restTemplate;
    private final StoreCacheRepository cacheRepository;
    private final CacheModeConfig cacheConfig;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 매장 정보 조회 (캐시 모드별 분기)
     * - NONE: 캐시 건너뛰고 직접 호출
     * - PULL/PUSH: Redis 캐시 우선 조회, 미스 시 모놀리스 Fallback
     */
    public Optional<StoreResponse> getStore(Long storeId) {
        // NONE 모드: 캐시 건너뛰고 직접 호출
        if (!cacheConfig.isCacheEnabled()) {
            log.debug("Store 조회 - NONE 모드, 직접 호출: storeId={}", storeId);
            return fetchFromMonolithDirect(storeId);
        }

        // PULL/PUSH 모드: 캐시 우선 조회
        Optional<StoreResponse> cached = cacheRepository.findById(storeId);
        if (cached.isPresent()) {
            return cached;
        }

        // Cache Miss → 모놀리스 Fallback
        return fetchFromMonolithAndCache(storeId);
    }

    /**
     * 모놀리스에서 Store 직접 조회 (NONE 모드용, 캐시 저장 안함)
     */
    @CircuitBreaker(name = "storeClient", fallbackMethod = "fetchFromMonolithFallback")
    @Retry(name = "storeClient", fallbackMethod = "fetchFromMonolithFallback")
    public Optional<StoreResponse> fetchFromMonolithDirect(Long storeId) {
        String url = monolithUrl + "/internal/stores/" + storeId;
        HttpHeaders headers = createHeaders();

        ResponseEntity<StoreResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                StoreResponse.class
        );

        return Optional.ofNullable(response.getBody());
    }

    /**
     * 모놀리스에서 Store 조회 후 캐시 저장
     */
    @CircuitBreaker(name = "storeClient", fallbackMethod = "fetchFromMonolithFallback")
    @Retry(name = "storeClient", fallbackMethod = "fetchFromMonolithFallback")
    public Optional<StoreResponse> fetchFromMonolithAndCache(Long storeId) {
        log.debug("Store 조회 - Cache Miss, 모놀리스 호출: storeId={}", storeId);
        String url = monolithUrl + "/internal/stores/" + storeId;

        HttpHeaders headers = createHeaders();

        ResponseEntity<StoreResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                StoreResponse.class
        );

        StoreResponse store = response.getBody();
        if (store != null) {
            cacheRepository.save(storeId, store);
            log.debug("Store 캐시 저장 완료: storeId={}", storeId);
        }

        return Optional.ofNullable(store);
    }

    private Optional<StoreResponse> fetchFromMonolithFallback(Long storeId, Throwable t) {
        log.error("Store 서비스 Fallback 호출: storeId={}, error={}", storeId, t.getMessage());
        throw new CustomException(ErrorCode.STORE_SERVICE_UNAVAILABLE, t);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
