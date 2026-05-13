package com.ssafy.keeping.qr.acl.webhook;

import com.ssafy.keeping.qr.acl.InternalAuthValidator;
import com.ssafy.keeping.qr.acl.cache.MenuCacheRepository;
import com.ssafy.keeping.qr.acl.cache.StoreCacheRepository;
import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.common.constants.HttpHeaderConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final InternalAuthValidator internalAuthValidator;

    /**
     * Store 캐시 갱신/삭제
     * - body가 있으면: 캐시 갱신
     * - body가 없으면: 캐시 삭제 (soft delete)
     */
    @PostMapping("/stores/{storeId}")
    public ResponseEntity<Void> updateStoreCache(
            @PathVariable Long storeId,
            @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false) String authToken,
            @RequestBody(required = false) StoreResponse store
    ) {
        internalAuthValidator.validate(authToken);

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
            @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false) String authToken,
            @RequestBody(required = false) MenuResponse menu
    ) {
        internalAuthValidator.validate(authToken);

        if (menu != null) {
            menuCacheRepository.save(menuId, menu);
            log.info("Menu 캐시 갱신 via webhook: menuId={}", menuId);
        } else {
            menuCacheRepository.evict(menuId);
            log.info("Menu 캐시 삭제 via webhook: menuId={}", menuId);
        }

        return ResponseEntity.ok().build();
    }

}
