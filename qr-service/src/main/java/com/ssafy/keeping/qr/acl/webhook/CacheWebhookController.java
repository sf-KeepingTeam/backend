package com.ssafy.keeping.qr.acl.webhook;

import com.ssafy.keeping.qr.acl.cache.MenuCacheRepository;
import com.ssafy.keeping.qr.acl.cache.StoreCacheRepository;
import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 캐시 Webhook 컨트롤러
 * 모놀리스에서 Store/Menu 변경 시 Push 방식으로 캐시 갱신
 */
@Slf4j
@RestController
@RequestMapping("/internal/cache")
@RequiredArgsConstructor
public class CacheWebhookController {

    private final StoreCacheRepository storeCacheRepository;
    private final MenuCacheRepository menuCacheRepository;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * Store 캐시 갱신/삭제
     * - body가 있으면: 캐시 갱신
     * - body가 없으면: 캐시 삭제 (soft delete)
     */
    @PostMapping("/stores/{storeId}")
    public ResponseEntity<Void> updateStoreCache(
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody(required = false) StoreResponse store
    ) {
        validateInternalAuth(authToken);

        if (store != null) {
            storeCacheRepository.save(storeId, store);
            log.info("Store 캐시 갱신 via webhook: storeId={}", storeId);
        } else {
            storeCacheRepository.evict(storeId);
            log.info("Store 캐시 삭제 via webhook: storeId={}", storeId);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Menu 캐시 갱신/삭제
     * - body가 있으면: 캐시 갱신
     * - body가 없으면: 캐시 삭제 (soft delete)
     */
    @PostMapping("/menus/{menuId}")
    public ResponseEntity<Void> updateMenuCache(
            @PathVariable Long menuId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody(required = false) MenuResponse menu
    ) {
        validateInternalAuth(authToken);

        if (menu != null) {
            menuCacheRepository.save(menuId, menu);
            log.info("Menu 캐시 갱신 via webhook: menuId={}", menuId);
        } else {
            menuCacheRepository.evict(menuId);
            log.info("Menu 캐시 삭제 via webhook: menuId={}", menuId);
        }

        return ResponseEntity.ok().build();
    }

    private void validateInternalAuth(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
