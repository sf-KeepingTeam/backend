package com.ssafy.keeping.qr.acl.cache;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** StoreCacheRepository version 비교 단위 테스트. Redis를 mock하여 version 비교 로직만 검증한다. */
@ExtendWith(MockitoExtension.class)
class StoreCacheRepositoryVersionTest {

  @Mock private RedisTemplate<String, Object> redisTemplate;
  @Mock private ValueOperations<String, Object> valueOps;

  private StoreCacheRepository repository;

  @BeforeEach
  void setUp() {
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    repository = new StoreCacheRepository(redisTemplate);
  }

  @Test
  @DisplayName("오래된 version의 update는 무시 — 최신 데이터를 stale webhook이 덮어쓰지 않음")
  void saveIfNewer_staleUpdate_ignored() {
    // given: 저장된 version = 2000
    when(valueOps.get("qr:stores:ver:1")).thenReturn(2000L);

    StoreResponse staleStore = new StoreResponse();
    staleStore.setStoreId(1L);
    staleStore.setStoreName("stale");
    staleStore.setVersion(1000L);

    // when: version 1000 (< 2000) 으로 saveIfNewer
    repository.saveIfNewer(1L, staleStore, 1000L);

    // then: 데이터 저장 안 됨 (set 호출 없음 — version 조회 외)
    verify(valueOps, never()).set(eq("qr:stores:1"), any(), any(Duration.class));
  }

  @Test
  @DisplayName("최신 version의 update는 정상 저장")
  void saveIfNewer_newerUpdate_saved() {
    // given: 저장된 version = 1000
    when(valueOps.get("qr:stores:ver:1")).thenReturn(1000L);

    StoreResponse newStore = new StoreResponse();
    newStore.setStoreId(1L);
    newStore.setStoreName("new");
    newStore.setVersion(2000L);

    // when: version 2000 (> 1000) 으로 saveIfNewer
    repository.saveIfNewer(1L, newStore, 2000L);

    // then: 데이터 + version 모두 저장
    verify(valueOps).set(eq("qr:stores:1"), eq(newStore), any(Duration.class));
    verify(valueOps).set(eq("qr:stores:ver:1"), eq(2000L), any(Duration.class));
  }

  @Test
  @DisplayName("delete 후 stale update 부활 방지 — tombstone이 stale update를 거부")
  void evictThenStaleUpdate_noResurrection() {
    // Step 1: evictIfNewer (version 3000) — delete + tombstone
    when(valueOps.get("qr:stores:ver:1")).thenReturn(null); // 처음엔 version 없음
    repository.evictIfNewer(1L, 3000L);

    // tombstone 기록 확인
    verify(redisTemplate).delete("qr:stores:1");
    verify(valueOps).set(eq("qr:stores:ver:1"), eq(3000L), any(Duration.class));

    // Step 2: stale update (version 2000) 시도 — tombstone(3000)보다 작아 무시
    reset(valueOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("qr:stores:ver:1")).thenReturn(3000L); // tombstone version

    StoreResponse staleStore = new StoreResponse();
    staleStore.setStoreId(1L);
    staleStore.setStoreName("deleted-but-stale");
    staleStore.setVersion(2000L);

    repository.saveIfNewer(1L, staleStore, 2000L);

    // then: 데이터 저장 안 됨 — 삭제된 매장이 부활하지 않음
    verify(valueOps, never()).set(eq("qr:stores:1"), any(), any(Duration.class));
  }

  @Test
  @DisplayName("version이 없는 상태(첫 저장)에서는 항상 저장")
  void saveIfNewer_noExistingVersion_saved() {
    // given: version 없음
    when(valueOps.get("qr:stores:ver:1")).thenReturn(null);

    StoreResponse store = new StoreResponse();
    store.setStoreId(1L);
    store.setStoreName("first");
    store.setVersion(1000L);

    // when
    repository.saveIfNewer(1L, store, 1000L);

    // then: 정상 저장
    verify(valueOps).set(eq("qr:stores:1"), eq(store), any(Duration.class));
    verify(valueOps).set(eq("qr:stores:ver:1"), eq(1000L), any(Duration.class));
  }
}
