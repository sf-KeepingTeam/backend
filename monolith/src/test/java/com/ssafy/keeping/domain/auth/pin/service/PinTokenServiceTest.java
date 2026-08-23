package com.ssafy.keeping.domain.auth.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.domain.auth.pin.dto.PinTokenResponse;
import com.ssafy.keeping.domain.auth.pin.service.PinRateLimiter;
import com.ssafy.keeping.domain.auth.token.JwtProperties;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PinTokenService 단위 테스트.
 *
 * <p>실행 금지 — 작성만 (세트 #41 제약). 인프라 담당이 재측정 시 실행.
 */
@ExtendWith(MockitoExtension.class)
class PinTokenServiceTest {

  private static final String JWT_SECRET =
      "test-secret-key-for-unit-testing-must-be-at-least-256-bits-long-1234567890";
  private static final String ISSUER = "kakao-oauth2-jwt";
  private static final long INTENT_TTL_SECONDS = 60L;
  private static final long SESSION_TTL_SECONDS = 180L;
  private static final Long CUSTOMER_ID = 42L;
  private static final String RAW_PIN = "123456";
  private static final String INTENT_PUBLIC_ID = "intent-abc-123";

  @Mock private PinAuthService pinAuthService;
  @Mock private PinRateLimiter pinRateLimiter;

  @InjectMocks private PinTokenService pinTokenService;

  private SecretKey signingKey;
  private PinTokenProperties pinTokenProperties;

  @BeforeEach
  void setUp() throws Exception {
    // JwtProperties는 record이므로 직접 생성
    JwtProperties jwtProperties = new JwtProperties(ISSUER, JWT_SECRET, 900L, 1209600L);

    // PinTokenProperties 기본값 설정 (session-token 꺼진 상태)
    pinTokenProperties = new PinTokenProperties();
    pinTokenProperties.setTokenTtlSeconds(INTENT_TTL_SECONDS);
    // sessionToken 기본값: enabled=false, ttlSeconds=180, maxUses=5, maxAmountPerUse=100000, maxAmountTotal=300000

    // @InjectMocks가 record/중첩 구조를 주입하지 못하므로 리플렉션으로 설정
    Field jwtPropsField = PinTokenService.class.getDeclaredField("jwtProperties");
    jwtPropsField.setAccessible(true);
    jwtPropsField.set(pinTokenService, jwtProperties);

    Field pinTokenPropsField = PinTokenService.class.getDeclaredField("pinTokenProperties");
    pinTokenPropsField.setAccessible(true);
    pinTokenPropsField.set(pinTokenService, pinTokenProperties);

    signingKey = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
  }

  // ===== 기존 테스트 (P-1 ~ P-6) =====

  // === P-1: 올바른 PIN + intentPublicId → 토큰 발급 ===

  @Test
  @DisplayName("P-1: 올바른 PIN과 intentPublicId로 토큰이 발급되고, 디코드하면 intentPublicId·jti·exp가 들어 있다")
  void verify_correct_pin_issues_token_with_expected_claims() {
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID);

    assertThat(response).isNotNull();
    assertThat(response.getPinToken()).isNotBlank();

    // 토큰 디코드 검증
    Claims claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer("keeping-pin-token")
            .build()
            .parseSignedClaims(response.getPinToken())
            .getPayload();

    assertThat(claims.getIssuer()).isEqualTo("keeping-pin-token");
    assertThat(claims.getSubject()).isEqualTo(String.valueOf(CUSTOMER_ID));
    assertThat(claims.get("intentPublicId", String.class)).isEqualTo(INTENT_PUBLIC_ID);
    assertThat(claims.getId()).isNotBlank(); // jti
    assertThat(claims.getExpiration()).isNotNull();
    assertThat(claims.getIssuedAt()).isNotNull();
  }

  // === P-2: 틀린 PIN → 토큰 미발급 + PIN_INVALID ===

  @Test
  @DisplayName("P-2: 틀린 PIN이면 토큰 미발급, PIN_INVALID 예외")
  void verify_wrong_pin_throws_pin_invalid() {
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(false);

    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_INVALID);
  }

  // === P-3: 잠금 상태 → PIN_LOCKED 전파 ===

  @Test
  @DisplayName("P-3: 잠금 상태에서 PIN이 맞아도 PIN_LOCKED 예외가 PinAuthService에서 전파된다")
  void verify_locked_state_propagates_pin_locked() {
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN))
        .thenThrow(new CustomException(ErrorCode.PIN_LOCKED));

    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_LOCKED);
  }

  // === P-4: 잠금 임계 도달 → PinAuthService가 처리 (위임 확인) ===

  @Test
  @DisplayName("P-4: 연속 실패로 잠금 임계에 도달하면 PinAuthService가 잠금 설정 후 false를 반환한다")
  void verify_lockout_threshold_delegates_to_pin_auth_service() {
    // PinAuthService가 내부에서 lockedUntil을 설정하고 false를 반환한다
    when(pinAuthService.verify(CUSTOMER_ID, "000000")).thenReturn(false);

    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, "000000", INTENT_PUBLIC_ID))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_INVALID);
  }

  // === P-5: 토큰 만료(TTL 경과) → 검증 실패 ===

  @Test
  @DisplayName("P-5: TTL을 0으로 설정하면 발급 즉시 만료되어 검증 시 ExpiredJwtException이 발생한다")
  void verify_expired_token_fails_validation() throws Exception {
    // tokenTtlSeconds를 0으로 설정하여 즉시 만료 토큰 발급
    pinTokenProperties.setTokenTtlSeconds(0L);

    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID);

    // 만료된 토큰 파싱 시 ExpiredJwtException
    assertThatThrownBy(
            () ->
                Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer("keeping-pin-token")
                    .build()
                    .parseSignedClaims(response.getPinToken()))
        .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
  }

  // === P-6: intentPublicId 없이 요청 (session-token.enabled=false) → INTENT_PUBLIC_ID_REQUIRED ===

  @Test
  @DisplayName("P-6: intentPublicId가 null이고 session-token 꺼져있으면 INTENT_PUBLIC_ID_REQUIRED 예외")
  void verify_null_intent_public_id_throws_when_session_disabled() {
    // pinTokenProperties.sessionToken.enabled=false (기본값)
    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);

    // PinAuthService.verify는 호출되지 않아야 한다
    verify(pinAuthService, never()).verify(anyLong(), anyString());
  }

  @Test
  @DisplayName("P-6: intentPublicId가 빈 문자열이고 session-token 꺼져있으면 INTENT_PUBLIC_ID_REQUIRED 예외")
  void verify_blank_intent_public_id_throws_when_session_disabled() {
    // pinTokenProperties.sessionToken.enabled=false (기본값)
    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, "   "))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);

    verify(pinAuthService, never()).verify(anyLong(), anyString());
  }

  // ===== 신규 테스트 (T-10 ~ T-15) — 세트 #41 =====

  /**
   * T-10: intentPublicId 있음 → intent 토큰 발급.
   * intentPublicId 클레임 있음, mu 없음, exp = iat + 60초.
   */
  @Test
  @DisplayName("T-10: intentPublicId 있으면 intent 토큰. intentPublicId 클레임 있음, mu 없음, exp=iat+60")
  void t10_with_intent_public_id_issues_intent_token() {
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID);

    assertThat(response.getPinToken()).isNotBlank();

    Claims claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(PinTokenService.PIN_TOKEN_ISSUER)
            .build()
            .parseSignedClaims(response.getPinToken())
            .getPayload();

    // intent 토큰: intentPublicId 있음
    assertThat(claims.get("intentPublicId", String.class)).isEqualTo(INTENT_PUBLIC_ID);

    // intent 토큰: mu 없음
    assertThat(claims.get("mu")).isNull();

    // exp = iat + 60초 (±2초 허용)
    long iat = claims.getIssuedAt().toInstant().getEpochSecond();
    long exp = claims.getExpiration().toInstant().getEpochSecond();
    assertThat(exp - iat).isBetween(INTENT_TTL_SECONDS - 2, INTENT_TTL_SECONDS + 2);

    // 응답에 maxUses=null (intent 토큰)
    assertThat(response.getMaxUses()).isNull();
    assertThat(response.getExpiresAtEpochSec()).isNotNull();
  }

  /**
   * T-11: intentPublicId null + session-token.enabled=true → 세션 토큰 발급.
   * intentPublicId 클레임 없음, mu=5, amax=100000, atot=300000, exp=iat+180.
   */
  @Test
  @DisplayName("T-11: intentPublicId null + enabled=true → 세션 토큰. mu=5, amax=100000, atot=300000, exp=iat+180")
  void t11_null_intent_with_session_enabled_issues_session_token() {
    enableSessionToken();
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null);

    assertThat(response.getPinToken()).isNotBlank();

    Claims claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(PinTokenService.PIN_TOKEN_ISSUER)
            .build()
            .parseSignedClaims(response.getPinToken())
            .getPayload();

    // 세션 토큰: intentPublicId 클레임 없음
    assertThat(claims.get("intentPublicId")).isNull();

    // 세션 토큰: mu, amax, atot 있음
    assertThat(claims.get("mu", Integer.class)).isEqualTo(5);
    assertThat(claims.get("amax", Long.class)).isEqualTo(100_000L);
    assertThat(claims.get("atot", Long.class)).isEqualTo(300_000L);

    // exp = iat + 180초 (±2초 허용)
    long iat = claims.getIssuedAt().toInstant().getEpochSecond();
    long exp = claims.getExpiration().toInstant().getEpochSecond();
    assertThat(exp - iat).isBetween(SESSION_TTL_SECONDS - 2, SESSION_TTL_SECONDS + 2);

    // 응답에 maxUses=5
    assertThat(response.getMaxUses()).isEqualTo(5);
    assertThat(response.getExpiresAtEpochSec()).isNotNull();
  }

  /**
   * T-12: intentPublicId 빈 문자열 + session-token.enabled=true → T-11과 동일.
   * null과 빈 문자열을 같게 취급해야 한다.
   */
  @Test
  @DisplayName("T-12: intentPublicId 빈 문자열 + enabled=true → T-11과 동일 (같게 취급)")
  void t12_blank_intent_with_session_enabled_issues_session_token() {
    enableSessionToken();
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, "");

    Claims claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(PinTokenService.PIN_TOKEN_ISSUER)
            .build()
            .parseSignedClaims(response.getPinToken())
            .getPayload();

    // 빈 문자열도 intent 없음 — 세션 토큰으로 발급됨
    assertThat(claims.get("intentPublicId")).isNull();
    assertThat(claims.get("mu", Integer.class)).isEqualTo(5);
    assertThat(claims.get("amax", Long.class)).isEqualTo(100_000L);
    assertThat(claims.get("atot", Long.class)).isEqualTo(300_000L);
    assertThat(response.getMaxUses()).isEqualTo(5);
  }

  /**
   * T-13: intentPublicId null + session-token.enabled=false → INTENT_PUBLIC_ID_REQUIRED.
   */
  @Test
  @DisplayName("T-13: intentPublicId null + enabled=false → INTENT_PUBLIC_ID_REQUIRED")
  void t13_null_intent_with_session_disabled_throws() {
    // pinTokenProperties.sessionToken.enabled=false (기본값)
    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);

    verify(pinAuthService, never()).verify(anyLong(), anyString());
  }

  /**
   * T-14: PIN 불일치 (세션 모드) → PIN_INVALID, 토큰 미발급.
   */
  @Test
  @DisplayName("T-14: PIN 불일치 (세션 모드) → PIN_INVALID. 토큰 발급 안 됨")
  void t14_wrong_pin_in_session_mode_throws_pin_invalid() {
    enableSessionToken();
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(false);

    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_INVALID);
  }

  /**
   * T-15: intent 토큰과 세션 토큰 모두 iss·sub·서명 키가 동일.
   * qr-service(#42)는 iss로 두 토큰을 동일하게 수락해야 한다.
   */
  @Test
  @DisplayName("T-15: intent 토큰과 세션 토큰 모두 iss·sub·서명 키가 동일")
  void t15_both_token_types_share_issuer_subject_and_key() {
    when(pinAuthService.verify(CUSTOMER_ID, RAW_PIN)).thenReturn(true);

    // intent 토큰
    PinTokenResponse intentResponse =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID);
    Claims intentClaims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(PinTokenService.PIN_TOKEN_ISSUER)
            .build()
            .parseSignedClaims(intentResponse.getPinToken())
            .getPayload();

    // 세션 토큰
    enableSessionToken();
    PinTokenResponse sessionResponse =
        pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null);
    Claims sessionClaims =
        Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(PinTokenService.PIN_TOKEN_ISSUER)
            .build()
            .parseSignedClaims(sessionResponse.getPinToken())
            .getPayload();

    // 동일한 iss
    assertThat(intentClaims.getIssuer()).isEqualTo(PinTokenService.PIN_TOKEN_ISSUER);
    assertThat(sessionClaims.getIssuer()).isEqualTo(PinTokenService.PIN_TOKEN_ISSUER);

    // 동일한 sub
    assertThat(intentClaims.getSubject()).isEqualTo(String.valueOf(CUSTOMER_ID));
    assertThat(sessionClaims.getSubject()).isEqualTo(String.valueOf(CUSTOMER_ID));

    // 두 토큰 모두 동일한 서명 키로 파싱됨 — 위 파싱이 예외 없이 통과했으면 OK
  }

  // ===== 신규 테스트 (R-10) — 세트 #43 =====

  /**
   * R-10: rate limit 초과 시 pinAuthService.verify()가 호출되지 않는다.
   *
   * <p>이 테스트가 세트 #43의 존재 이유를 증명한다.
   * PinRateLimiter가 Argon2 실행(pinAuthService.verify) 앞에 있어야만 통과한다.
   */
  @Test
  @DisplayName("R-10: rate limit 초과 시 Argon2(pinAuthService.verify)가 실행되지 않는다")
  void r10_rate_limit_exceeded_prevents_argon2_execution() {
    // rate limit 초과 상황 시뮬레이션
    doThrow(new CustomException(ErrorCode.PIN_TOKEN_RATE_LIMITED))
        .when(pinRateLimiter).checkAndIncrement(CUSTOMER_ID);

    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, INTENT_PUBLIC_ID))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_TOKEN_RATE_LIMITED);

    // ★ 핵심 검증: Argon2가 실행되지 않았다
    verifyNoInteractions(pinAuthService);
  }

  // ===== 헬퍼 =====

  /** 테스트용 session-token 활성화. */
  private void enableSessionToken() {
    pinTokenProperties.getSessionToken().setEnabled(true);
  }
}
