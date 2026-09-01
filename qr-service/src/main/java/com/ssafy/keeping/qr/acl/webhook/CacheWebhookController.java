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
 * 캐시 Webhook 컨트롤러 모놀리스에서 Store/Menu 변경 시 Push 방식으로 캐시 갱신 X-Cache-Version 헤더로 단조 version 비교 — 재정렬·중복
 * 방어
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
   * Store 캐시 갱신/삭제 - body가 있으면: version 비교 후 캐시 갱신 - body가 없으면: version 비교 후 캐시 삭제 (tombstone 유지)
   */
  @PostMapping("/stores/{storeId}")
  public ResponseEntity<Void> updateStoreCache(
      @PathVariable Long storeId,
      @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false)
          String authToken,
      @RequestHeader(
              value = HttpHeaderConstants.X_CACHE_VERSION,
              required = false,
              defaultValue = "0")
          long version,
      @RequestBody(required = false) StoreResponse store) {
    internalAuthValidator.validate(authToken);

    if (store != null) {
      storeCacheRepository.saveIfNewer(storeId, store, version);
      log.info("Store 캐시 갱신 via webhook: storeId={}, version={}", storeId, version);
    } else {
      storeCacheRepository.evictIfNewer(storeId, version);
      log.info("Store 캐시 삭제 via webhook: storeId={}, version={}", storeId, version);
    }

    return ResponseEntity.ok().build();
  }

  /**
   * Menu 캐시 갱신/삭제 - body가 있으면: version 비교 후 캐시 갱신 - body가 없으면: version 비교 후 캐시 삭제 (tombstone 유지)
   */
  @PostMapping("/menus/{menuId}")
  public ResponseEntity<Void> updateMenuCache(
      @PathVariable Long menuId,
      @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false)
          String authToken,
      @RequestHeader(
              value = HttpHeaderConstants.X_CACHE_VERSION,
              required = false,
              defaultValue = "0")
          long version,
      @RequestBody(required = false) MenuResponse menu) {
    internalAuthValidator.validate(authToken);

    if (menu != null) {
      menuCacheRepository.saveIfNewer(menuId, menu, version);
      log.info("Menu 캐시 갱신 via webhook: menuId={}, version={}", menuId, version);
    } else {
      menuCacheRepository.evictIfNewer(menuId, version);
      log.info("Menu 캐시 삭제 via webhook: menuId={}, version={}", menuId, version);
    }

    return ResponseEntity.ok().build();
  }
}
