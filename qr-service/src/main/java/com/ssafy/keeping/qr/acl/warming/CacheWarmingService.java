package com.ssafy.keeping.qr.acl.warming;

import com.ssafy.keeping.qr.acl.cache.MenuCacheRepository;
import com.ssafy.keeping.qr.acl.cache.StoreCacheRepository;
import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.config.CacheModeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 캐시 워밍 서비스
 * 애플리케이션 시작 시 모놀리스에서 전체 Store/Menu 데이터를 가져와 캐시에 적재
 * PUSH 모드에서만 캐시 워밍 실행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheWarmingService {

    private final RestTemplate restTemplate;
    private final StoreCacheRepository storeCacheRepository;
    private final MenuCacheRepository menuCacheRepository;
    private final CacheModeConfig cacheConfig;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    @Value("${cache.warming.enabled:true}")
    private boolean warmingEnabled;

    /**
     * 애플리케이션 시작 시 캐시 워밍 실행
     * PUSH 모드에서만 실행
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        // PUSH 모드가 아니면 캐시 워밍 건너뜀
        if (!cacheConfig.isPushEnabled()) {
            log.info("캐시 워밍 건너뜀 - 캐시 모드: {}", cacheConfig.getMode());
            return;
        }

        if (!warmingEnabled) {
            log.info("캐시 워밍 비활성화됨 (cache.warming.enabled=false)");
            return;
        }

        log.info("캐시 워밍 시작...");

        try {
            warmStoreCache();
            warmMenuCache();
            log.info("캐시 워밍 완료");
        } catch (Exception e) {
            log.error("캐시 워밍 실패: {}", e.getMessage(), e);
        }
    }

    private void warmStoreCache() {
        log.info("Store 캐시 워밍 시작...");
        try {
            String url = monolithUrl + "/internal/stores/all";
            HttpHeaders headers = createHeaders();

            ResponseEntity<List<StoreResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<List<StoreResponse>>() {}
            );

            List<StoreResponse> stores = response.getBody() != null ? response.getBody() : Collections.emptyList();
            storeCacheRepository.saveAll(stores);
            log.info("Store 캐시 워밍 완료: {} 건", stores.size());
        } catch (Exception e) {
            log.error("Store 캐시 워밍 실패: {}", e.getMessage());
        }
    }

    private void warmMenuCache() {
        log.info("Menu 캐시 워밍 시작...");
        try {
            String url = monolithUrl + "/internal/menus/all";
            HttpHeaders headers = createHeaders();

            ResponseEntity<List<MenuResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<List<MenuResponse>>() {}
            );

            List<MenuResponse> menus = response.getBody() != null ? response.getBody() : Collections.emptyList();
            menuCacheRepository.saveAll(menus);
            log.info("Menu 캐시 워밍 완료: {} 건", menus.size());
        } catch (Exception e) {
            log.error("Menu 캐시 워밍 실패: {}", e.getMessage());
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
