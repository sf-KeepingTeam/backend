package com.ssafy.keeping.domain.auth.pin.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * PIN 검증 성공 시 단기 서명 토큰을 발급한다.
 *
 * <p>토큰은 qr-service가 결제 승인 시 PIN 재검증 없이 로컬 서명 검증만으로 본인 확인을 대체한다.
 * 서명 키는 두 서비스가 공유하는 JWT_SECRET(JwtProperties.secret())을 그대로 사용한다.
 *
 * <h3>토큰 클레임 계약 (세트 #33 qr-service 검증용)</h3>
 * <ul>
 *   <li>{@code iss} = {@code "keeping-pin-token"} — access token의 issuer와 구별</li>
 *   <li>{@code sub} = customerId (String)</li>
 *   <li>{@code intentPublicId} = 결제 요청(intent)의 publicId (String)</li>
 *   <li>{@code jti} = UUID (1회용 식별자 — 기록은 qr-service가 담당)</li>
 *   <li>{@code iat} = 발급 시각</li>
 *   <li>{@code exp} = iat + TTL (기본 60초, {@code payment.pin.token-ttl-seconds})</li>
 * </ul>
 * <p>서명: HMAC-SHA256, 키 = {@code JwtProperties.secret()} UTF-8 바이트</p>
 */
@Service
@RequiredArgsConstructor
public class PinTokenService {

  private static final String PIN_TOKEN_ISSUER = "keeping-pin-token";

  private final PinAuthService pinAuthService;
  private final JwtProperties jwtProperties;

  @Value("${payment.pin.token-ttl-seconds:60}")
  private long tokenTtlSeconds;

  /**
   * PIN을 검증하고, 성공 시 단기 서명 토큰을 발급한다.
   *
   * @param customerId 인증된 고객 ID (SecurityContext에서 추출)
   * @param rawPin 평문 PIN
   * @param intentPublicId 결제 요청의 publicId
   * @return 서명 토큰을 담은 응답
   * @throws CustomException PIN_REQUIRED, PIN_NOT_SET, PIN_LOCKED, INTENT_PUBLIC_ID_REQUIRED
   */
  public PinTokenResponse verifyAndIssueToken(Long customerId, String rawPin, String intentPublicId) {
    // intentPublicId 필수 검증
    if (intentPublicId == null || intentPublicId.isBlank()) {
      throw new CustomException(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);
    }

    // PIN 검증 (잠금 정책·실패 카운트 로직은 PinAuthService가 담당)
    boolean verified = pinAuthService.verify(customerId, rawPin);
    if (!verified) {
      throw new CustomException(ErrorCode.PIN_INVALID);
    }

    // 토큰 발급
    String token = buildPinToken(customerId, intentPublicId);
    return PinTokenResponse.of(token);
  }

  private String buildPinToken(Long customerId, String intentPublicId) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(tokenTtlSeconds);

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
}
