package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.cache.MenuCacheRepository;
import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.config.CacheModeConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Menu 서비스를 HTTP로 호출
 *
 * 캐시 모드별 동작:
 * - NONE: 캐시 미사용, 항상 모놀리스 직접 호출
 * - PULL: Cache-Aside (캐시 미스 시 조회 후 저장)
 * - PUSH: Webhook Push + Cache-Aside Fallback
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuClient {

    private final RestTemplate restTemplate;
    private final MenuCacheRepository cacheRepository;
    private final CacheModeConfig cacheConfig;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 메뉴 목록 일괄 조회 (캐시 모드별 분기)
     * - NONE: 캐시 건너뛰고 직접 호출
     * - PULL/PUSH: Redis 캐시 우선 조회, 미스 시 모놀리스 Fallback
     */
    public List<MenuResponse> getMenus(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return Collections.emptyList();
        }

        // NONE 모드: 캐시 건너뛰고 직접 호출
        if (!cacheConfig.isCacheEnabled()) {
            log.debug("Menu 조회 - NONE 모드, 직접 호출: menuIds={}", menuIds);
            return fetchFromMonolithDirect(menuIds);
        }

        // PULL/PUSH 모드: Redis 캐시에서 조회 시도
        List<MenuResponse> cachedMenus = new ArrayList<>();
        List<Long> missingIds = new ArrayList<>();

        for (Long menuId : menuIds) {
            Optional<MenuResponse> cached = cacheRepository.findById(menuId);
            if (cached.isPresent()) {
                cachedMenus.add(cached.get());
            } else {
                missingIds.add(menuId);
            }
        }

        // 모든 메뉴가 캐시에 있으면 반환
        if (missingIds.isEmpty()) {
            log.debug("Menu 조회 - 전체 캐시 HIT: count={}", cachedMenus.size());
            return cachedMenus;
        }

        // Cache Miss된 메뉴만 모놀리스에서 조회
        log.debug("Menu 조회 - 부분 캐시 MISS: cached={}, missing={}", cachedMenus.size(), missingIds.size());
        List<MenuResponse> fetchedMenus = fetchFromMonolithAndCache(missingIds);
        cachedMenus.addAll(fetchedMenus);

        return cachedMenus;
    }

    /**
     * 모놀리스에서 Menu 일괄 직접 조회 (NONE 모드용, 캐시 저장 안함)
     */
    @CircuitBreaker(name = "menuClient", fallbackMethod = "fetchFromMonolithFallback")
    @Retry(name = "menuClient", fallbackMethod = "fetchFromMonolithFallback")
    public List<MenuResponse> fetchFromMonolithDirect(List<Long> menuIds) {
        log.debug("Menu 일괄 조회 - NONE 모드, 직접 호출: menuIds={}", menuIds);
        String url = monolithUrl + "/internal/menus/batch";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");

        BatchMenuRequest body = new BatchMenuRequest(menuIds);

        ResponseEntity<List<MenuResponse>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<List<MenuResponse>>() {}
        );

        return response.getBody() != null ? response.getBody() : Collections.emptyList();
    }

    /**
     * 단일 메뉴 조회 (캐시 모드별 분기)
     * - NONE: 캐시 건너뛰고 직접 호출
     * - PULL/PUSH: Redis 캐시 우선 조회, 미스 시 모놀리스 Fallback
     */
    public Optional<MenuResponse> getMenu(Long menuId) {
        // NONE 모드: 캐시 건너뛰고 직접 호출
        if (!cacheConfig.isCacheEnabled()) {
            log.debug("Menu 조회 - NONE 모드, 직접 호출: menuId={}", menuId);
            return fetchSingleFromMonolithDirect(menuId);
        }

        // PULL/PUSH 모드: Redis 캐시 조회
        Optional<MenuResponse> cached = cacheRepository.findById(menuId);
        if (cached.isPresent()) {
            return cached;
        }

        // Cache Miss → 모놀리스 Fallback
        return fetchSingleFromMonolithAndCache(menuId);
    }

    /**
     * 모놀리스에서 단일 Menu 직접 조회 (NONE 모드용, 캐시 저장 안함)
     */
    @CircuitBreaker(name = "menuClient", fallbackMethod = "fetchSingleFromMonolithFallback")
    @Retry(name = "menuClient", fallbackMethod = "fetchSingleFromMonolithFallback")
    public Optional<MenuResponse> fetchSingleFromMonolithDirect(Long menuId) {
        String url = monolithUrl + "/internal/menus/" + menuId;
        HttpHeaders headers = createHeaders();

        ResponseEntity<MenuResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                MenuResponse.class
        );

        return Optional.ofNullable(response.getBody());
    }

    /**
     * 모놀리스에서 Menu 일괄 조회 후 캐시 저장
     */
    @CircuitBreaker(name = "menuClient", fallbackMethod = "fetchFromMonolithFallback")
    @Retry(name = "menuClient", fallbackMethod = "fetchFromMonolithFallback")
    public List<MenuResponse> fetchFromMonolithAndCache(List<Long> menuIds) {
        log.debug("Menu 일괄 조회 - 모놀리스 호출: menuIds={}", menuIds);
        String url = monolithUrl + "/internal/menus/batch";

        HttpHeaders headers = createHeaders();
        headers.set("Content-Type", "application/json");

        BatchMenuRequest body = new BatchMenuRequest(menuIds);

        ResponseEntity<List<MenuResponse>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<List<MenuResponse>>() {}
        );

        List<MenuResponse> menus = response.getBody() != null ? response.getBody() : Collections.emptyList();

        // 캐시에 저장
        menus.forEach(menu -> cacheRepository.save(menu.getMenuId(), menu));
        log.debug("Menu 캐시 저장 완료: count={}", menus.size());

        return menus;
    }

    /**
     * 모놀리스에서 단일 Menu 조회 후 캐시 저장
     */
    @CircuitBreaker(name = "menuClient", fallbackMethod = "fetchSingleFromMonolithFallback")
    @Retry(name = "menuClient", fallbackMethod = "fetchSingleFromMonolithFallback")
    public Optional<MenuResponse> fetchSingleFromMonolithAndCache(Long menuId) {
        log.debug("Menu 단일 조회 - 모놀리스 호출: menuId={}", menuId);
        String url = monolithUrl + "/internal/menus/" + menuId;

        HttpHeaders headers = createHeaders();

        ResponseEntity<MenuResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                MenuResponse.class
        );

        MenuResponse menu = response.getBody();
        if (menu != null) {
            cacheRepository.save(menuId, menu);
            log.debug("Menu 캐시 저장 완료: menuId={}", menuId);
        }

        return Optional.ofNullable(menu);
    }

    private List<MenuResponse> fetchFromMonolithFallback(List<Long> menuIds, Throwable t) {
        log.error("Menu 서비스 Fallback 호출: menuIds={}, error={}", menuIds, t.getMessage());
        throw new CustomException(ErrorCode.MENU_SERVICE_UNAVAILABLE, t);
    }

    private Optional<MenuResponse> fetchSingleFromMonolithFallback(Long menuId, Throwable t) {
        log.error("Menu 서비스 Fallback 호출: menuId={}, error={}", menuId, t.getMessage());
        throw new CustomException(ErrorCode.MENU_SERVICE_UNAVAILABLE, t);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }

    private record BatchMenuRequest(List<Long> menuIds) {}
}
