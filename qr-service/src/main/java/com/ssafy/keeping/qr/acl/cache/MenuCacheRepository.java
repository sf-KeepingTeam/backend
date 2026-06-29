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
 * 단조 version 비교로 재정렬·중복 방어 + tombstone으로 삭제 부활 방지
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MenuCacheRepository {

    private static final String PREFIX = "qr:menus:";
    private static final String VERSION_PREFIX = "qr:menus:ver:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration TOMBSTONE_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Menu 캐시 저장 (version 비교 — cache warming용, 기존 version보다 높을 때만 저장)
     * warming 데이터는 실제 version을 가져 올바르게 저장됨.
     */
    public void save(Long menuId, MenuResponse menu) {
        saveIfNewer(menuId, menu, menu.getVersion());
    }

    /**
     * version이 저장된 version보다 클 때만 save.
     * // TODO: 멀티 스레드 환경에서 version 비교+갱신 원자성 보장을 위해 Redis Lua 스크립트 도입 권장
     */
    public void saveIfNewer(Long menuId, MenuResponse menu, long version) {
        String verKey = VERSION_PREFIX + menuId;
        Long storedVersion = getStoredVersion(verKey);

        if (storedVersion != null && version <= storedVersion) {
            log.debug("Menu 캐시 업데이트 무시 (stale): menuId={}, incoming={}, stored={}",
                    menuId, version, storedVersion);
            return;
        }

        String key = PREFIX + menuId;
        redisTemplate.opsForValue().set(key, menu, TTL);
        redisTemplate.opsForValue().set(verKey, version, TTL);
        log.debug("Menu 캐시 저장: menuId={}, version={}", menuId, version);
    }

    /**
     * version이 저장된 version보다 클 때만 evict + tombstone.
     * // TODO: 멀티 스레드 환경에서 version 비교+갱신 원자성 보장을 위해 Redis Lua 스크립트 도입 권장
     */
    public void evictIfNewer(Long menuId, long version) {
        String verKey = VERSION_PREFIX + menuId;
        Long storedVersion = getStoredVersion(verKey);

        if (storedVersion != null && version <= storedVersion) {
            log.debug("Menu 캐시 삭제 무시 (stale): menuId={}, incoming={}, stored={}",
                    menuId, version, storedVersion);
            return;
        }

        String key = PREFIX + menuId;
        redisTemplate.delete(key);
        redisTemplate.opsForValue().set(verKey, version, TOMBSTONE_TTL);
        log.debug("Menu 캐시 삭제 + tombstone: menuId={}, version={}", menuId, version);
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
     * Menu 캐시 삭제 (version 무관 — 관리용)
     */
    public void evict(Long menuId) {
        String key = PREFIX + menuId;
        String verKey = VERSION_PREFIX + menuId;
        redisTemplate.delete(key);
        redisTemplate.delete(verKey);
        log.debug("Menu 캐시 삭제: menuId={}", menuId);
    }

    /**
     * 전체 Menu 캐시 저장 (Cache Warming용)
     * 실제 version(updatedAt epoch millis)으로 저장.
     */
    public void saveAll(List<MenuResponse> menus) {
        menus.forEach(menu -> save(menu.getMenuId(), menu));
        log.info("Menu 캐시 일괄 저장 완료: count={}", menus.size());
    }

    private Long getStoredVersion(String verKey) {
        Object val = redisTemplate.opsForValue().get(verKey);
        if (val instanceof Number num) {
            return num.longValue();
        }
        if (val instanceof String str) {
            try { return Long.parseLong(str); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
