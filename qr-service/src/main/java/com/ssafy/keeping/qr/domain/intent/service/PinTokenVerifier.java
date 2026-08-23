package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.config.JwtProperties;
import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * monolith가 발급한 PIN 검증 토큰을 로컬에서 검증한다.
 *
 * <p>검증 순서:
 * <ol>
 *   <li>서명 검증 (JWT_SECRET, HMAC-SHA256)
 *   <li>exp 만료 검증 (jjwt 파서 자동 처리)
 *   <li>issuer 검증 ({@code "keeping-pin-token"})
 *   <li>intentPublicId 클레임과 요청 경로의 intentPublicId 대조
 *   <li>jti 1회용 검증 — Redis SETNX로 기록, 이미 있으면 재사용 차단
 * </ol>
 *
 * <p>[NO-TX] 구간에서 호출. JPA 리포지토리 호출 없음. Redis 왕복 1회.
 */
@Slf4j
@Component
public class PinTokenVerifier {

    private static final String PIN_TOKEN_ISSUER = "keeping-pin-token";
    private static final String JTI_KEY_PREFIX = "pin-token:jti:";

    private final JwtProperties jwtProperties;
    private final PaymentTuningProperties tuningProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public PinTokenVerifier(
            JwtProperties jwtProperties,
            PaymentTuningProperties tuningProperties,
            StringRedisTemplate stringRedisTemplate) {
        this.jwtProperties = jwtProperties;
        this.tuningProperties = tuningProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * PIN 토큰을 검증한다.
     *
     * @param pinToken         JWT 문자열
     * @param intentPublicId   요청 경로의 intentPublicId
     * @return 토큰에서 추출한 customerId
     * @throws CustomException 검증 실패 시
     */
    public Long verify(String pinToken, UUID intentPublicId) {
        // 1-3. 서명 + exp + issuer 검증
        Claims claims;
        try {
            SecretKey key = Keys.hmacShaKeyFor(
                    jwtProperties.secret().getBytes(StandardCharsets.UTF_8));

            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(PIN_TOKEN_ISSUER)
                    .build()
                    .parseSignedClaims(pinToken)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[PIN_TOKEN] 만료된 토큰: {}", e.getMessage());
            throw new CustomException(ErrorCode.PIN_TOKEN_EXPIRED);
        } catch (JwtException e) {
            log.warn("[PIN_TOKEN] 유효하지 않은 토큰: {}", e.getMessage());
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        // 4. intentPublicId 대조
        String tokenIntentPublicId = claims.get("intentPublicId", String.class);
        if (tokenIntentPublicId == null
                || !tokenIntentPublicId.equals(intentPublicId.toString())) {
            log.warn("[PIN_TOKEN] intent 불일치: token={}, request={}",
                    tokenIntentPublicId, intentPublicId);
            throw new CustomException(ErrorCode.PIN_TOKEN_INTENT_MISMATCH);
        }

        // 5. jti 1회용 검증 — Redis SETNX (왕복 1회)
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            log.warn("[PIN_TOKEN] jti 클레임 없음");
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        // TTL = 토큰 수명 + 30초 (시계 오차 보상)
        int ttlSeconds = tuningProperties.getPin().getTokenTtlSeconds() + 30;
        String redisKey = JTI_KEY_PREFIX + jti;

        Boolean wasAbsent = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", Duration.ofSeconds(ttlSeconds));

        if (wasAbsent == null || !wasAbsent) {
            log.warn("[PIN_TOKEN] 재사용된 jti: {}", jti);
            throw new CustomException(ErrorCode.PIN_TOKEN_REUSED);
        }

        // customerId 추출
        Long customerId = Long.parseLong(claims.getSubject());
        log.info("[PIN_TOKEN] 검증 성공: customerId={}, intentPublicId={}, jti={}",
                customerId, intentPublicId, jti);

        return customerId;
    }
}
