package com.ssafy.keeping.domain.auth.pin.service;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.domain.auth.pin.dto.PinTokenResponse;
import com.ssafy.keeping.domain.auth.token.JwtProperties;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * PIN 검증 성공 시 단기 서명 토큰을 발급한다.
 *
 * <p>토큰은 qr-service가 결제 승인 시 PIN 재검증 없이 로컬 서명 검증만으로 본인 확인을 대체한다.
 * 서명 키는 두 서비스가 공유하는 JWT_SECRET(JwtProperties.secret())을 그대로 사용한다.
 * 키를 분리하지 마라 — 두 서비스가 공유하는 값이고 분리하면 배포가 꼬인다.
 *
 * <h3>토큰 종류</h3>
 * <dl>
 *   <dt>intent 토큰 (기존)</dt>
 *   <dd>{@code intentPublicId}가 있을 때 발급. TTL=60초. {@code mu}/{@code amax}/{@code atot} 없음.</dd>
 *   <dt>세션 토큰 (신규, {@code session-token.enabled=true} 일 때만)</dt>
 *   <dd>{@code intentPublicId}가 없거나 빈 문자열일 때 발급. TTL=180초.
 *       {@code mu}/{@code amax}/{@code atot} 포함 (서버 설정값, 클라이언트 입력 불가).</dd>
 * </dl>
 *
 * <h3>토큰 클레임 계약 (세트 #41/#42 공통)</h3>
 * <table border="1">
 *   <tr><th>claim</th><th>intent 토큰</th><th>세션 토큰</th></tr>
 *   <tr><td>{@code iss}</td><td>{@code keeping-pin-token}</td><td>동일 — 바꾸지 마라</td></tr>
 *   <tr><td>{@code sub}</td><td>customerId</td><td>customerId</td></tr>
 *   <tr><td>{@code jti}</td><td>UUID</td><td>UUID</td></tr>
 *   <tr><td>{@code intentPublicId}</td><td>있음</td><td><b>없음</b> (빈 문자열로도 넣지 마라)</td></tr>
 *   <tr><td>{@code mu}</td><td>없음</td><td>maxUses (Integer)</td></tr>
 *   <tr><td>{@code amax}</td><td>없음</td><td>maxAmountPerUse (Long)</td></tr>
 *   <tr><td>{@code atot}</td><td>없음</td><td>maxAmountTotal (Long)</td></tr>
 * </table>
 *
 * <p>검증측(qr-service #42)은 {@code mu} 클레임 부재 = intent 토큰 = 1회용으로 취급한다.
 * {@code iss}를 바꾸면 배포 순서에 따라 전부 거부된다. 바꾸지 마라.
 */
@Service
@RequiredArgsConstructor
public class PinTokenService {

  static final String PIN_TOKEN_ISSUER = "keeping-pin-token";

  private final PinAuthService pinAuthService;
  private final PinRateLimiter pinRateLimiter;
  private final JwtProperties jwtProperties;
  private final PinTokenProperties pinTokenProperties;

  /**
   * PIN을 검증하고, 성공 시 단기 서명 토큰을 발급한다.
   *
   * <p>분기 규칙:
   * <ul>
   *   <li>{@code intentPublicId}가 있다 → intent 토큰 (기존 동작 그대로)</li>
   *   <li>{@code intentPublicId}가 없거나 빈 문자열 + {@code session-token.enabled=true} → 세션 토큰</li>
   *   <li>{@code intentPublicId}가 없거나 빈 문자열 + {@code session-token.enabled=false} → INTENT_PUBLIC_ID_REQUIRED</li>
   * </ul>
   *
   * <p>PIN 검증({@code pinAuthService.verify})은 두 경로 모두 그대로 거친다.
   *
   * @param customerId     인증된 고객 ID (SecurityContext에서 추출)
   * @param rawPin         평문 PIN
   * @param intentPublicId 결제 요청의 publicId. null 또는 빈 문자열이면 세션 토큰 경로로 분기
   * @return 서명 토큰과 만료 정보를 담은 응답
   * @throws CustomException PIN_REQUIRED, PIN_NOT_SET, PIN_LOCKED, PIN_INVALID,
   *                         INTENT_PUBLIC_ID_REQUIRED
   */
  public PinTokenResponse verifyAndIssueToken(Long customerId, String rawPin, String intentPublicId) {
    // 1. ★ rate limit — pinAuthService.verify()(Argon2) 보다 반드시 앞에 있어야 한다.
    //    여기서 막지 않으면 공격자가 올바른 PIN으로 Argon2를 무제한 실행해 CPU를 고갈시킨다.
    pinRateLimiter.checkAndIncrement(customerId);

    boolean hasIntent = intentPublicId != null && !intentPublicId.isBlank();

    if (!hasIntent && !pinTokenProperties.getSessionToken().isEnabled()) {
      // session-token 꺼진 상태에서 intentPublicId 없음 → 기존 에러
      throw new CustomException(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);
    }

    // PIN 검증 (잠금 정책·실패 카운트 로직은 PinAuthService가 담당)
    // 두 경로 모두 반드시 거친다 — 건너뛰지 마라
    boolean verified = pinAuthService.verify(customerId, rawPin);
    if (!verified) {
      throw new CustomException(ErrorCode.PIN_INVALID);
    }

    if (hasIntent) {
      return buildIntentTokenResponse(customerId, intentPublicId);
    } else {
      return buildSessionTokenResponse(customerId);
    }
  }

  /**
   * intent 토큰을 발급한다 (기존 동작 유지).
   *
   * <p>{@code intentPublicId} 클레임 포함, TTL=tokenTtlSeconds(60초), {@code mu}/{@code amax}/{@code atot} 없음.
   */
  private PinTokenResponse buildIntentTokenResponse(Long customerId, String intentPublicId) {
    Instant now = Instant.now();
    long ttl = pinTokenProperties.getTokenTtlSeconds();
    Instant exp = now.plusSeconds(ttl);

    String token = buildIntentToken(customerId, intentPublicId, now, exp);
    return PinTokenResponse.of(token, exp.getEpochSecond(), null);
  }

  /**
   * 세션 토큰을 발급한다 ({@code session-token.enabled=true} 경로).
   *
   * <p>{@code intentPublicId} 클레임 없음 (빈 문자열로도 넣지 않는다 — 검증측이 클레임 존재=intent 토큰으로 판정).
   * TTL=ttlSeconds(180초), {@code mu}/{@code amax}/{@code atot} 서버 설정값으로 채운다.
   */
  private PinTokenResponse buildSessionTokenResponse(Long customerId) {
    Instant now = Instant.now();
    var st = pinTokenProperties.getSessionToken();
    Instant exp = now.plusSeconds(st.getTtlSeconds());

    String token = buildSessionToken(customerId, now, exp, st);
    return PinTokenResponse.of(token, exp.getEpochSecond(), st.getMaxUses());
  }

  private String buildIntentToken(Long customerId, String intentPublicId, Instant now, Instant exp) {
    byte[] keyBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
    var key = Keys.hmacShaKeyFor(keyBytes);

    return Jwts.builder()
        .issuer(PIN_TOKEN_ISSUER)
        .subject(String.valueOf(customerId))
        .claim("intentPublicId", intentPublicId)
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .signWith(key)
        .compact();
  }

  private String buildSessionToken(
      Long customerId,
      Instant now,
      Instant exp,
      PinTokenProperties.SessionToken st) {
    byte[] keyBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
    var key = Keys.hmacShaKeyFor(keyBytes);

    // intentPublicId 클레임을 넣지 마라 — 빈 문자열로도 넣지 마라.
    // 검증측(#42)은 "클레임 존재 = intent 토큰"으로 판정한다.
    return Jwts.builder()
        .issuer(PIN_TOKEN_ISSUER)
        .subject(String.valueOf(customerId))
        .claim("mu",   st.getMaxUses())
        .claim("amax", st.getMaxAmountPerUse())
        .claim("atot", st.getMaxAmountTotal())
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .signWith(key)
        .compact();
  }
}
