package com.ssafy.keeping.qr.acl.cache;

import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Store 캐시 저장소
 * Push 기반 캐싱: 모놀리스에서 Webhook으로 갱신
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StoreCacheRepository {

    private static final String PREFIX = "qr:stores:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Store 캐시 저장
     */
    public void save(Long storeId, StoreResponse store) {
        String key = PREFIX + storeId;
        redisTemplate.opsForValue().set(key, store, TTL);
        log.debug("Store 캐시 저장: storeId={}", storeId);
    }

    /**
     * Store 캐시 조회
     */
    public Optional<StoreResponse> findById(Long storeId) {
        String key = PREFIX + storeId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof StoreResponse store) {
            log.debug("Store 캐시 HIT: storeId={}", storeId);
            return Optional.of(store);
        }
        log.debug("Store 캐시 MISS: storeId={}", storeId);
        return Optional.empty();
    }

    /**
     * Store 캐시 삭제
     */
    public void evict(Long storeId) {
        String key = PREFIX + storeId;
        redisTemplate.delete(key);
        log.debug("Store 캐시 삭제: storeId={}", storeId);
    }

    /**
     * 전체 Store 캐시 저장 (Cache Warming용)
     */
    public void saveAll(List<StoreResponse> stores) {
        stores.forEach(store -> save(store.getStoreId(), store));
        log.info("Store 캐시 일괄 저장 완료: count={}", stores.size());
    }
}
