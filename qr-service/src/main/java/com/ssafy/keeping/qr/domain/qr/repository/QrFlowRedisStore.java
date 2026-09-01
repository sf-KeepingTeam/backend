package com.ssafy.keeping.qr.domain.qr.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalCacheValue;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * QR 결제 플로우 보조 Redis 키 저장소.
 *
 * <p>네 가지 키를 관리한다:
 *
 * <ul>
 *   <li>{@code qrflow:s2t:{sessionToken}} = tokenId — 점주 스캔 시 저장. initiate 에서 역참조.
 *   <li>{@code qrflow:active:{tokenId}} = "1" — 점주 스캔 시 저장. 롱폴링 404 판별용.
 *   <li>{@code qrflow:intent:{tokenId}} = JSON — AFTER_COMMIT 리스너가 저장. poll/즉시 해소용.
 *   <li>{@code qrflow:i2t:{intentPublicId}} = tokenId — approve 후 intent → token 역참조용.
 * </ul>
 *
 * 모두 TTL 180 초 (스캔 세션과 동일).
 */
@Slf4j
@Repository
public class QrFlowRedisStore {

    private static final String S2T_PREFIX    = "qrflow:s2t:";
    private static final String ACTIVE_PREFIX = "qrflow:active:";
    private static final String INTENT_PREFIX = "qrflow:intent:";
    private static final String I2T_PREFIX    = "qrflow:i2t:";
    private static final long   TTL_SECONDS   = 180L;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public QrFlowRedisStore(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

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
     * <p>값 형식: JSON ({@link IntentArrivalCacheValue} 직렬화).
     * poll / 즉시 해소 경로에서 DB 왕복 없이 응답을 구성하는 데 사용한다.
     */
    public void saveIntentArrival(String tokenId, IntentArrivalCacheValue value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(
                    INTENT_PREFIX + tokenId, json, Duration.ofSeconds(TTL_SECONDS));
        } catch (JsonProcessingException e) {
            log.warn("[QR_FLOW] intent 캐시 직렬화 실패 — tokenId={} error={}", tokenId, e.getMessage());
        }
    }

    /**
     * intentPublicId → tokenId 역참조 키 저장 (TTL 180 s).
     *
     * <p>approve 성공 시 {@code qrflow:intent} / {@code qrflow:active} 를 DEL 하기 위해 사용.
     */
    public void saveI2TMapping(String intentPublicId, String tokenId) {
        redisTemplate.opsForValue().set(
                I2T_PREFIX + intentPublicId, tokenId, Duration.ofSeconds(TTL_SECONDS));
    }

    // ── approve 성공 후 정리 ──────────────────────────────────────────

    /**
     * intentPublicId 에 대응하는 tokenId 반환.
     *
     * @param intentPublicId UUID 문자열
     * @return tokenId, 없으면 empty
     */
    public Optional<String> getTokenIdForIntent(String intentPublicId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(I2T_PREFIX + intentPublicId));
    }

    /**
     * 결제 승인 후 롱폴링 키를 제거한다.
     *
     * <p>승인된 뒤에도 손님 앱이 같은 tokenId 로 롱폴링하면
     * 이미 처리된 intent 로 200 OK 를 다시 받게 된다. 이를 방지한다.
     * TTL(180 s) 이전에 명시적으로 DEL 한다.
     */
    public void deleteIntentArrivalKeys(String tokenId) {
        redisTemplate.delete(INTENT_PREFIX + tokenId);
        redisTemplate.delete(ACTIVE_PREFIX + tokenId);
    }

    // ── 롱폴링 등록 직후 즉시 체크 ────────────────────────────────────

    /**
     * tokenId 에 대한 intent 도착 여부를 단건 GET 으로 즉시 확인.
     *
     * <p>waiter 등록 전에 initiate 가 이미 완료된 경우, 폴링 200 ms 를 기다리지 않고
     * 즉시 해소할 수 있도록 register() 직후 호출한다.
     *
     * @param tokenId QR 플로우 tokenId
     * @return 캐시 값, 없으면 empty
     */
    public Optional<IntentArrivalCacheValue> getIntentArrivalDirect(String tokenId) {
        String json = redisTemplate.opsForValue().get(INTENT_PREFIX + tokenId);
        if (json == null) return Optional.empty();
        return parseJson(tokenId, json);
    }

    // ── 폴링 스케줄러에서 호출 ─────────────────────────────────────────

    /**
     * 복수 tokenId 에 대해 intent 도착 여부를 MGET 으로 일괄 조회.
     *
     * @param tokenIds 조회할 tokenId 목록
     * @return 입력 순서와 1:1 대응하는 캐시 값 목록 (미도착 시 null)
     */
    public List<IntentArrivalCacheValue> mgetIntentArrival(List<String> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = tokenIds.stream()
                .map(t -> INTENT_PREFIX + t)
                .toList();
        List<String> jsonValues = redisTemplate.opsForValue().multiGet(keys);
        if (jsonValues == null) {
            return Collections.nCopies(tokenIds.size(), null);
        }
        List<IntentArrivalCacheValue> result = new java.util.ArrayList<>(jsonValues.size());
        for (int i = 0; i < jsonValues.size(); i++) {
            String json = jsonValues.get(i);
            if (json == null) {
                result.add(null);
            } else {
                result.add(parseJson(tokenIds.get(i), json).orElse(null));
            }
        }
        return result;
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────

    private Optional<IntentArrivalCacheValue> parseJson(String tokenId, String json) {
        try {
            return Optional.of(objectMapper.readValue(json, IntentArrivalCacheValue.class));
        } catch (JsonProcessingException e) {
            log.warn("[QR_FLOW] intent 캐시 역직렬화 실패 — tokenId={} error={}", tokenId, e.getMessage());
            return Optional.empty();
        }
    }
}
