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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * monolith 가 발급한 PIN 검증 토큰을 로컬에서 검증한다.
 *
 * <p>검증 순서:
 * <ol>
 *   <li>서명 검증 (JWT_SECRET, HMAC-SHA256) + exp 만료 + issuer
 *   <li>sub == authenticatedCustomerId 대조 (주체 검증 — 세트 #40)
 *   <li>{@code intentPublicId} 클레임 유무로 모드 판정
 *       <ul>
 *         <li>있음 → intent 토큰: 경로의 intentPublicId 와 대조 (하위호환)
 *         <li>없음 → 세션 토큰: 4~5 단계 적용
 *       </ul>
 *   <li>amax 건당 금액 대조 (Redis 왕복 0 — 클레임만 본다)
 *   <li>Lua 1회 왕복: revoke 확인 + use INCR + amt INCRBY
 * </ol>
 *
 * <p>Redis 키:
 * <ul>
 *   <li>{@code pin-token:use:{jti}} — 사용 횟수 (TTL = token exp − now + 30초)
 *   <li>{@code pin-token:amt:{jti}} — 누적 금액 (같은 TTL)
 *   <li>{@code pin-token:revoke:{customerId}} — 폐기 시각 (monolith 가 관리, 읽기만)
 * </ul>
 *
 * <p>[NO-TX] 구간에서 호출. JPA 리포지토리 호출 0건. Redis 왕복 경로당 1회.
 *
 * <p>Redis 장애 시 예외를 잡지 않고 전파한다 — 재사용 공격 방지가 가용성보다 우선.
 * 기존 동작과 동일하며 의도된 것이다 (설계 §V2-8 참조).
 */
@Slf4j
@Component
public class PinTokenVerifier {

    private static final String PIN_TOKEN_ISSUER = "keeping-pin-token";

    // Redis 키 접두어 — 기존 jti: 키는 더 이상 쓰지 않는다 (use: 로 대체)
    private static final String USE_KEY_PREFIX    = "pin-token:use:";
    private static final String AMT_KEY_PREFIX    = "pin-token:amt:";
    private static final String REVOKE_KEY_PREFIX = "pin-token:revoke:";

    /**
     * Lua 스크립트 — Redis 왕복 1회로 횟수·금액·폐기를 원자적으로 검사한다.
     *
     * <p>KEYS[1] = pin-token:use:{jti}
     * KEYS[2] = pin-token:amt:{jti}
     * KEYS[3] = pin-token:revoke:{customerId}
     * ARGV[1] = maxUses        ARGV[2] = amount        ARGV[3] = maxAmountTotal
     * ARGV[4] = ttlSeconds     ARGV[5] = iat(epochSec) ARGV[6] = revocationCheck(0|1)
     *
     * <p>반환: 0=OK  1=REUSED(횟수초과)  2=AMOUNT_EXCEEDED(누적금액초과)  3=REVOKED
     */
    private static final DefaultRedisScript<Long> LUA_SCRIPT;

    static {
        LUA_SCRIPT = new DefaultRedisScript<>();
        LUA_SCRIPT.setResultType(Long.class);
        LUA_SCRIPT.setScriptText(
            "if ARGV[6] == '1' then\n" +
            "  local rev = redis.call('GET', KEYS[3])\n" +
            "  if rev and tonumber(rev) > tonumber(ARGV[5]) then return 3 end\n" +
            "end\n" +
            "\n" +
            "local uses = redis.call('INCR', KEYS[1])\n" +
            "if uses == 1 then redis.call('EXPIRE', KEYS[1], ARGV[4]) end\n" +
            "if uses > tonumber(ARGV[1]) then return 1 end\n" +
            "\n" +
            "if tonumber(ARGV[3]) > 0 then\n" +
            "  local total = redis.call('INCRBY', KEYS[2], ARGV[2])\n" +
            "  if total == tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[2], ARGV[4]) end\n" +
            "  if total > tonumber(ARGV[3]) then return 2 end\n" +
            "end\n" +
            "\n" +
            "return 0"
        );
    }

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
     * <p>intent 토큰과 세션 토큰을 모두 처리한다.
     * {@code intentPublicId} 클레임의 유무로 판정한다:
     * <ul>
     *   <li>있음 → intent 토큰: mu 없음 = 1회용, intentPublicId 대조 필수
     *   <li>없음 → 세션 토큰: mu·amax·atot 클레임 적용
     * </ul>
     *
     * @param pinToken                JWT 문자열
     * @param intentPublicId          요청 경로의 intentPublicId
     * @param authenticatedCustomerId 인증된 고객 ID (JWT sub 와 대조하는 인증 주체)
     * @param amount                  이번 결제 금액 (amax 건당 검사 및 Lua INCRBY 에 사용)
     * @return 토큰에서 추출한 customerId
     * @throws CustomException 검증 실패 시
     */
    public Long verify(String pinToken, UUID intentPublicId,
                       Long authenticatedCustomerId, long amount) {

        // ── 1. 서명 + exp + iss 검증 ─────────────────────────────────
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

        // ── 2. sub == authenticatedCustomerId 대조 (주체 검증 — 세트 #40) ──
        // authenticatedCustomerId 가 null 이면 호출부 실수 — 조용히 통과시키지 않는다.
        Long tokenSub;
        try {
            String subStr = claims.getSubject();
            if (subStr == null || subStr.isBlank()) {
                throw new NumberFormatException("null or blank");
            }
            tokenSub = Long.parseLong(subStr);
        } catch (NumberFormatException e) {
            log.warn("[PIN_TOKEN] sub 클레임 파싱 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        if (!Objects.equals(tokenSub, authenticatedCustomerId)) {
            log.warn("[PIN_TOKEN] reject=SUBJECT_MISMATCH tokenSub={} authSub={} jti={}",
                    tokenSub, authenticatedCustomerId, claims.getId());
            throw new CustomException(ErrorCode.PIN_TOKEN_SUBJECT_MISMATCH);
        }

        // ── 3. intentPublicId 클레임 유무로 모드 판정 ────────────────
        String tokenIntentPublicId = claims.get("intentPublicId", String.class);
        boolean isIntentToken = (tokenIntentPublicId != null && !tokenIntentPublicId.isBlank());
        String scope = isIntentToken ? "intent" : "session";

        // intent 토큰: 경로의 intentPublicId 와 대조 (하위호환)
        if (isIntentToken) {
            if (!tokenIntentPublicId.equals(intentPublicId.toString())) {
                log.warn("[PIN_TOKEN] intent 불일치: token={}, request={}",
                        tokenIntentPublicId, intentPublicId);
                throw new CustomException(ErrorCode.PIN_TOKEN_INTENT_MISMATCH);
            }
        }
        // 세션 토큰: intentPublicId 를 보지 않는다 (K-30 — 통과)

        // ── 숫자 클레임 추출 (T-3: jjwt 는 Integer 로 역직렬화할 수 있다) ──
        int maxUses;
        long maxAmountPerUse;
        long maxAmountTotal;
        try {
            Number muNum    = claims.get("mu",   Number.class);
            Number amaxNum  = claims.get("amax", Number.class);
            Number atotNum  = claims.get("atot", Number.class);

            // mu 없음 = 1회용 (intent 토큰 또는 mu 미설정 토큰)
            maxUses         = (muNum   == null) ? 1 : muNum.intValue();
            // amax/atot 없음 = 0 = 무제한
            maxAmountPerUse = (amaxNum == null) ? 0L : amaxNum.longValue();
            maxAmountTotal  = (atotNum == null) ? 0L : atotNum.longValue();
        } catch (Exception e) {
            // ClassCastException 등 변환 실패 → 500 으로 새면 안 된다
            log.warn("[PIN_TOKEN] 클레임 숫자 변환 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        // jti 검증 (횟수·금액·폐기 모두 Lua 로 처리하므로 여기서는 형식만 확인)
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            log.warn("[PIN_TOKEN] jti 클레임 없음");
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        // ── 4. amax 건당 금액 대조 — Redis 왕복 0. 클레임만 본다. ──────
        // 싼 검사를 먼저: 금액 초과가 확실한 요청이 Redis 예산을 소모하면 안 된다.
        if (maxAmountPerUse > 0 && amount > maxAmountPerUse) {
            log.warn("[PIN_TOKEN] reject=AMOUNT_EXCEEDED jti={} amount={} amax={}",
                    jti, amount, maxAmountPerUse);
            throw new CustomException(ErrorCode.PIN_TOKEN_AMOUNT_EXCEEDED);
        }

        // ── 5. Lua 1회 왕복: revoke 확인 + use INCR + amt INCRBY ────
        // TTL = 토큰 자신의 exp − now + 30초 (설정값이 아니라 exp 기준 — 세션 토큰은 TTL 이 다르다)
        long ttl = claims.getExpiration().toInstant().getEpochSecond()
                 - Instant.now().getEpochSecond() + 30;
        if (ttl < 1) ttl = 1; // 만료 직전 토큰의 음수 TTL 방어

        long iat = claims.getIssuedAt() != null
                ? claims.getIssuedAt().toInstant().getEpochSecond()
                : 0L;

        boolean revocationCheck = tuningProperties.getPin().getSessionToken().isRevocationCheck();

        String useKey    = USE_KEY_PREFIX    + jti;
        String amtKey    = AMT_KEY_PREFIX    + jti;
        String revokeKey = REVOKE_KEY_PREFIX + tokenSub;

        // Redis 예외는 잡지 않는다 — 재사용 공격 방지 > 가용성 (설계 §V2-8 참조)
        Long luaResult = stringRedisTemplate.execute(
                LUA_SCRIPT,
                List.of(useKey, amtKey, revokeKey),
                String.valueOf(maxUses),
                String.valueOf(amount),
                String.valueOf(maxAmountTotal),
                String.valueOf(ttl),
                String.valueOf(iat),
                revocationCheck ? "1" : "0"
        );

        // null 반환 = Redis 응답 이상 — 통과시키지 않는다. 모르면 거부한다.
        if (luaResult == null) {
            log.warn("[PIN_TOKEN] reject=LUA_NULL jti={}", jti);
            throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        switch (luaResult.intValue()) {
            case 0:
                // 통과
                break;
            case 1:
                log.warn("[PIN_TOKEN] reject=REUSED jti={} maxUses={}", jti, maxUses);
                throw new CustomException(ErrorCode.PIN_TOKEN_REUSED);
            case 2:
                log.warn("[PIN_TOKEN] reject=AMOUNT_EXCEEDED jti={} amount={} atot={}",
                        jti, amount, maxAmountTotal);
                throw new CustomException(ErrorCode.PIN_TOKEN_AMOUNT_EXCEEDED);
            case 3:
                log.warn("[PIN_TOKEN] reject=REVOKED jti={} iat={}", jti, iat);
                throw new CustomException(ErrorCode.PIN_TOKEN_REVOKED);
            default:
                log.warn("[PIN_TOKEN] reject=UNKNOWN_LUA_RESULT result={} jti={}", luaResult, jti);
                throw new CustomException(ErrorCode.PIN_TOKEN_INVALID);
        }

        // uses=ok/maxUses: Lua 가 0을 반환했다 = 한 번 더 소모했다 (실제 카운터는 Redis에 있음)
        // 인프라가 결제 건수 대비 verify-token 호출 수를 이 로그로 센다 — 삭제 금지
        log.info("[PIN_TOKEN] scope={} sub={} jti={} uses=ok/{} amount={} amountTotal={}/{}",
                scope, tokenSub, jti,
                maxUses,
                amount, amount, maxAmountTotal);

        return tokenSub;
    }
}
