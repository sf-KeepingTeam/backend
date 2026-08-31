package com.ssafy.keeping.qr.domain.qr.repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * QR 결제 플로우 보조 Redis 키 저장소.
 *
 * <p>세 가지 키를 관리한다:
 *
 * <ul>
 *   <li>{@code qrflow:s2t:{sessionToken}} = tokenId — 점주 스캔 시 저장. initiate 에서 역참조.
 *   <li>{@code qrflow:active:{tokenId}} = "1" — 점주 스캔 시 저장. 롱폴링 404 판별용.
 *   <li>{@code qrflow:intent:{tokenId}} = "{intentPublicId}:{customerId}" — AFTER_COMMIT 리스너가 저장.
 * </ul>
 *
 * 모두 TTL 180 초 (스캔 세션과 동일).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class QrFlowRedisStore {

    private static final String S2T_PREFIX    = "qrflow:s2t:";
    private static final String ACTIVE_PREFIX = "qrflow:active:";
    private static final String INTENT_PREFIX = "qrflow:intent:";
    private static final long   TTL_SECONDS   = 180L;

    private final RedisTemplate<String, String> redisTemplate;

    // ── scan 에서 호출 ─────────────────────────────────────────────────

    /** sessionToken → tokenId 매핑 저장 (TTL 180 s). */
    public void saveSessionToTokenMapping(String sessionToken, String tokenId) {
        redisTemplate.opsForValue().set(
                S2T_PREFIX + sessionToken, tokenId, Duration.ofSeconds(TTL_SECONDS));
    }

    /** tokenId 활성 마킹 (TTL 180 s). 롱폴링 엔드포인트의 404 판별에 사용. */
    public void saveActiveToken(String tokenId) {
        redisTemplate.opsForValue().set(
                ACTIVE_PREFIX + tokenId, "1", Duration.ofSeconds(TTL_SECONDS));
    }

    // ── initiate 에서 호출 ─────────────────────────────────────────────

    /** sessionToken 에 대응하는 tokenId 를 반환. s2t 키가 없으면 empty. */
    public Optional<String> getTokenIdForSession(String sessionToken) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(S2T_PREFIX + sessionToken));
    }

    // ── 롱폴링 컨트롤러 + 리스너에서 호출 ────────────────────────────────

    /** tokenId 가 활성(스캔 완료) 상태인지 확인. */
    public boolean isActiveToken(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_PREFIX + tokenId));
    }

    // ── AFTER_COMMIT 리스너에서 호출 ──────────────────────────────────

    /**
     * intent 도착 기록 (TTL 180 s).
     *
     * <p>값 형식: {@code "{intentPublicId}:{customerId}"}.
     * UUID 는 "-" 만 포함하므로 ":" 구분자와 충돌 없음.
     */
    public void saveIntentArrival(String tokenId, UUID intentPublicId, Long customerId) {
        String value = intentPublicId.toString() + ":" + customerId;
        redisTemplate.opsForValue().set(
                INTENT_PREFIX + tokenId, value, Duration.ofSeconds(TTL_SECONDS));
    }

    // ── 롱폴링 등록 직후 즉시 체크 ────────────────────────────────────

    /**
     * tokenId 에 대한 intent 도착 여부를 단건 GET 으로 즉시 확인.
     *
     * <p>waiter 등록 전에 initiate 가 이미 완료된 경우, 폴링 200 ms 를 기다리지 않고
     * 즉시 해소할 수 있도록 register() 직후 호출한다.
     *
     * @param tokenId QR 플로우 tokenId
     * @return 값이 있으면 {@code "{intentPublicId}:{customerId}"}, 없으면 empty
     */
    public Optional<String> getIntentArrivalDirect(String tokenId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(INTENT_PREFIX + tokenId));
    }

    // ── 폴링 스케줄러에서 호출 ─────────────────────────────────────────

    /**
     * 복수 tokenId 에 대해 intent 도착 여부를 MGET 으로 일괄 조회.
     *
     * @param tokenIds 조회할 tokenId 목록
     * @return 입력 순서와 1:1 대응하는 값 목록 (미도착 시 null)
     */
    public List<String> mgetIntentArrival(List<String> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = tokenIds.stream()
                .map(t -> INTENT_PREFIX + t)
                .collect(Collectors.toList());
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        return values == null ? Collections.nCopies(tokenIds.size(), null) : values;
    }
}
