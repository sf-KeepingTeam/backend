package com.ssafy.keeping.qr.acl.cache;

import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Menu 캐시 저장소
 * Push 기반 캐싱: 모놀리스에서 Webhook으로 갱신
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MenuCacheRepository {

    private static final String PREFIX = "qr:menus:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Menu 캐시 저장
     */
    public void save(Long menuId, MenuResponse menu) {
        String key = PREFIX + menuId;
        redisTemplate.opsForValue().set(key, menu, TTL);
        log.debug("Menu 캐시 저장: menuId={}, storeId={}", menuId, menu.getStoreId());
    }

    /**
     * Menu 캐시 조회
     */
    public Optional<MenuResponse> findById(Long menuId) {
        String key = PREFIX + menuId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof MenuResponse menu) {
            log.debug("Menu 캐시 HIT: menuId={}", menuId);
            return Optional.of(menu);
        }
        log.debug("Menu 캐시 MISS: menuId={}", menuId);
        return Optional.empty();
    }

    /**
     * Menu 캐시 삭제
     */
    public void evict(Long menuId) {
        String key = PREFIX + menuId;
        redisTemplate.delete(key);
        log.debug("Menu 캐시 삭제: menuId={}", menuId);
    }

    /**
     * 전체 Menu 캐시 저장 (Cache Warming용)
     */
    public void saveAll(List<MenuResponse> menus) {
        menus.forEach(menu -> save(menu.getMenuId(), menu));
        log.info("Menu 캐시 일괄 저장 완료: count={}", menus.size());
    }
}
