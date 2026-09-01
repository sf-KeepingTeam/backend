package com.ssafy.keeping.qr.acl.cache;

import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/** Store 캐시 저장소 Push 기반 캐싱: 모놀리스에서 Webhook으로 갱신 단조 version 비교로 재정렬·중복 방어 + tombstone으로 삭제 부활 방지 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StoreCacheRepository {

  private static final String PREFIX = "qr:stores:";
  private static final String VERSION_PREFIX = "qr:stores:ver:";
  private static final Duration TTL = Duration.ofHours(24);
  private static final Duration TOMBSTONE_TTL = Duration.ofHours(24);

  private final RedisTemplate<String, Object> redisTemplate;

  /** Store 캐시 저장 (version 비교 — cache warming용) warming 데이터는 실제 version을 가져 올바르게 저장됨. */
  public void save(Long storeId, StoreResponse store) {
    saveIfNewer(storeId, store, store.getVersion());
  }

  /**
   * version이 저장된 version보다 클 때만 save. 오래된 webhook이 최신 데이터를 덮어쓰지 못한다. // TODO: 멀티 스레드 환경에서 version
   * 비교+갱신 원자성 보장을 위해 Redis Lua 스크립트 도입 권장
   */
  public void saveIfNewer(Long storeId, StoreResponse store, long version) {
    String verKey = VERSION_PREFIX + storeId;
    Long storedVersion = getStoredVersion(verKey);

    if (storedVersion != null && version <= storedVersion) {
      log.debug(
          "Store 캐시 업데이트 무시 (stale): storeId={}, incoming={}, stored={}",
          storeId,
          version,
          storedVersion);
      return;
    }

    String key = PREFIX + storeId;
    redisTemplate.opsForValue().set(key, store, TTL);
    redisTemplate.opsForValue().set(verKey, version, TTL);
    log.debug("Store 캐시 저장: storeId={}, version={}", storeId, version);
  }

  /**
   * version이 저장된 version보다 클 때만 evict. 데이터는 삭제하되 tombstone(version 마커)을 유지하여 stale update의 부활을 방지.
   * // TODO: 멀티 스레드 환경에서 version 비교+갱신 원자성 보장을 위해 Redis Lua 스크립트 도입 권장
   */
  public void evictIfNewer(Long storeId, long version) {
    String verKey = VERSION_PREFIX + storeId;
    Long storedVersion = getStoredVersion(verKey);

    if (storedVersion != null && version <= storedVersion) {
      log.debug(
          "Store 캐시 삭제 무시 (stale): storeId={}, incoming={}, stored={}",
          storeId,
          version,
          storedVersion);
      return;
    }

    String key = PREFIX + storeId;
    redisTemplate.delete(key);
    // tombstone: version 마커만 남겨 이후 stale update 거부
    redisTemplate.opsForValue().set(verKey, version, TOMBSTONE_TTL);
    log.debug("Store 캐시 삭제 + tombstone: storeId={}, version={}", storeId, version);
  }

  /** Store 캐시 조회 */
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

  /** Store 캐시 삭제 (version 무관 — 관리용) */
  public void evict(Long storeId) {
    String key = PREFIX + storeId;
    String verKey = VERSION_PREFIX + storeId;
    redisTemplate.delete(key);
    redisTemplate.delete(verKey);
    log.debug("Store 캐시 삭제: storeId={}", storeId);
  }

  /** 전체 Store 캐시 저장 (Cache Warming용) 실제 version(updatedAt epoch millis)으로 저장. */
  public void saveAll(List<StoreResponse> stores) {
    stores.forEach(store -> save(store.getStoreId(), store));
    log.info("Store 캐시 일괄 저장 완료: count={}", stores.size());
  }

  private Long getStoredVersion(String verKey) {
    Object val = redisTemplate.opsForValue().get(verKey);
    if (val instanceof Number num) {
      return num.longValue();
    }
    if (val instanceof String str) {
      try {
        return Long.parseLong(str);
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }
}
