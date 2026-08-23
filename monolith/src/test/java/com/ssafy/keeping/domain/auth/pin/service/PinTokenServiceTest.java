package com.ssafy.keeping.domain.auth.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.auth.pin.dto.PinTokenResponse;
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
 * <p>실행 금지 — 작성만 (세트 #32 제약). 인프라 담당이 재측정 시 실행.
 */
@ExtendWith(MockitoExtension.class)
class PinTokenServiceTest {

  private static final String JWT_SECRET =
      "test-secret-key-for-unit-testing-must-be-at-least-256-bits-long-1234567890";
  private static final String ISSUER = "kakao-oauth2-jwt";
  private static final long TTL_SECONDS = 60L;
  private static final Long CUSTOMER_ID = 42L;
  private static final String RAW_PIN = "123456";
  private static final String INTENT_PUBLIC_ID = "intent-abc-123";

  @Mock private PinAuthService pinAuthService;

  @InjectMocks private PinTokenService pinTokenService;

  private SecretKey signingKey;

  @BeforeEach
  void setUp() throws Exception {
    // JwtProperties는 record이므로 직접 생성
    JwtProperties jwtProperties = new JwtProperties(ISSUER, JWT_SECRET, 900L, 1209600L);

    // @InjectMocks가 record를 주입하지 못하므로 리플렉션으로 설정
    Field jwtPropsField = PinTokenService.class.getDeclaredField("jwtProperties");
    jwtPropsField.setAccessible(true);
    jwtPropsField.set(pinTokenService, jwtProperties);

    Field ttlField = PinTokenService.class.getDeclaredField("tokenTtlSeconds");
    ttlField.setAccessible(true);
    ttlField.set(pinTokenService, TTL_SECONDS);

    signingKey = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
  }

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
    // TTL을 0으로 설정하여 즉시 만료 토큰 발급
    Field ttlField = PinTokenService.class.getDeclaredField("tokenTtlSeconds");
    ttlField.setAccessible(true);
    ttlField.set(pinTokenService, 0L);

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

  // === P-6: intentPublicId 없이 요청 → 400 ===

  @Test
  @DisplayName("P-6: intentPublicId가 null이면 INTENT_PUBLIC_ID_REQUIRED 예외")
  void verify_null_intent_public_id_throws() {
    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, null))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);

    // PinAuthService.verify는 호출되지 않아야 한다
    verify(pinAuthService, never()).verify(anyLong(), anyString());
  }

  @Test
  @DisplayName("P-6: intentPublicId가 빈 문자열이면 INTENT_PUBLIC_ID_REQUIRED 예외")
  void verify_blank_intent_public_id_throws() {
    assertThatThrownBy(
            () -> pinTokenService.verifyAndIssueToken(CUSTOMER_ID, RAW_PIN, "   "))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTENT_PUBLIC_ID_REQUIRED);

    verify(pinAuthService, never()).verify(anyLong(), anyString());
  }
}
