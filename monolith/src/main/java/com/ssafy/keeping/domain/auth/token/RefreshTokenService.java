package com.ssafy.keeping.domain.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String TOKEN_PREFIX = "auth:refresh:token:";   // token -> payload
    private static final String ACTIVE_PREFIX = "auth:refresh:active:"; // (role:id) -> token (싱글세션용)

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final JwtProperties props;

    public record RefreshTokenPayload(long userId, UserRole role, long issuedAtEpochSec) {}
    public record IssuedRefreshToken(String token, long ttlSeconds, RefreshTokenPayload payload) {}

    /**
     * 한 사용자당 refresh 토큰 1개만 유지
     * - 새로 발급하면 기존 토큰은 무효화
     * @param userId
     * @param role
     * @return
     */
    public IssuedRefreshToken issueSingleSession(long userId, UserRole role) {
        String activeKey = ACTIVE_PREFIX + role.name() + ":" + userId;

        // 기존 토큰 있으면 폐기
        String oldToken = redis.opsForValue().get(activeKey);
        if (oldToken != null && !oldToken.isBlank()) {
            redis.delete(TOKEN_PREFIX + oldToken);
        }

        String newToken = UUID.randomUUID().toString();
        String tokenKey = TOKEN_PREFIX + newToken;

        RefreshTokenPayload payload = new RefreshTokenPayload(userId, role, Instant.now().getEpochSecond());

        long ttl = props.refreshTtlSeconds();
        Duration duration = Duration.ofSeconds(ttl);

        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(tokenKey, json, duration);
            redis.opsForValue().set(activeKey, newToken, duration); // activeKey도 같은 TTL
            return new IssuedRefreshToken(newToken, ttl, payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue refresh token", e);
        }
    }

    public RefreshTokenPayload verify(String refreshToken) {
        String tokenKey = TOKEN_PREFIX + refreshToken;

        String json = redis.opsForValue().get(tokenKey);
        if (json == null) return null;

        try {
            RefreshTokenPayload payload = objectMapper.readValue(json, RefreshTokenPayload.class);

            String activeKey = ACTIVE_PREFIX + payload.role().name() + ":" + payload.userId();
            String activeToken = redis.opsForValue().get(activeKey);

            // ACTIVE에 등록된 토큰과 다르면(= 구세션/비정상) 바로 무효 처리
            if (activeToken == null || !refreshToken.equals(activeToken)) {
                redis.delete(tokenKey);
                return null;
            }

            return payload;
        } catch (Exception e) {
            redis.delete(TOKEN_PREFIX + refreshToken);
            return null;
        }
    }

    /**
     * refresh 로테이션 (쿠키 기반 재발급에서 사용)
     * 1) refreshToken -> payload 검증
     * 2) ACTIVE(role:id)에 저장된 "현재 토큰"인지 확인
     * 3) 새 토큰 발급 + TOKEN/ACTIVE 갱신 + 기존 토큰 삭제
     */
    public IssuedRefreshToken rotateSingleSession(String refreshToken) {
        RefreshTokenPayload payload = verify(refreshToken);
        if (payload == null) return null;

        String activeKey = ACTIVE_PREFIX + payload.role().name() + ":" + payload.userId();
        String activeToken = redis.opsForValue().get(activeKey);

        // ACTIVE에 등록된 토큰이 아니면 -> 무효 처리
        if (activeToken == null || !activeToken.equals(refreshToken)) {
            redis.delete(TOKEN_PREFIX + refreshToken); // 방어적으로 tokenKey도 삭제 시도(있으면)
            return null;
        }

        // 새 토큰 발급
        String newToken = UUID.randomUUID().toString();
        String newTokenKey = TOKEN_PREFIX + newToken;

        long ttl = props.refreshTtlSeconds();
        Duration duration = Duration.ofSeconds(ttl);

        RefreshTokenPayload newPayload =
                new RefreshTokenPayload(payload.userId(), payload.role(), Instant.now().getEpochSecond());

        try {
            String json = objectMapper.writeValueAsString(newPayload);

            redis.opsForValue().set(newTokenKey, json, duration); // 1) 새 토큰 저장
            redis.opsForValue().set(activeKey, newToken, duration); // 2) ACTIVE 갱신
            redis.delete(TOKEN_PREFIX + refreshToken); // 3) 기존 토큰 폐기

            return new IssuedRefreshToken(newToken, ttl, newPayload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rotate refresh token", e);
        }
    }

    /**
     * 로그아웃용: TOKEN/ACTIVE 둘 다 제거
     * - 로그아웃은 여러 번 호출되어도 성공(idempotent)하는 게 UX가 좋음
     */
    public void logoutSingleSession(String refreshToken) {
        RefreshTokenPayload payload = verify(refreshToken);

        redis.delete(TOKEN_PREFIX + refreshToken);

        if (payload != null) {
            String activeKey = ACTIVE_PREFIX + payload.role().name() + ":" + payload.userId();
            redis.delete(activeKey);
        }
    }

    /**
     * application.yaml 설정값 그대로 반환
     * @return Refresh Token(JWT) 유효시간
     */
    public long refreshTtlSeconds() {
        return props.refreshTtlSeconds();
    }

}
