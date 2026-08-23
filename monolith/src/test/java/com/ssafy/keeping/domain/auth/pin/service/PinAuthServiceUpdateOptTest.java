package com.ssafy.keeping.domain.auth.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.domain.auth.pin.model.CustomerPinAuth;
import com.ssafy.keeping.domain.auth.pin.repository.CustomerPinAuthRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PinAuthService §4-6 최적화 검증 + 세트 #41 revocation 기록 검증.
 *
 * <p>실행 금지 — 작성만 (세트 #41 제약).
 */
@ExtendWith(MockitoExtension.class)
class PinAuthServiceUpdateOptTest {

  @Mock private CustomerPinAuthRepository customerPinAuthRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private Clock clock;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  @InjectMocks private PinAuthService pinAuthService;

  private static final Long CUSTOMER_ID = 42L;
  private static final String RAW_PIN = "123456";
  private static final String PIN_HASH = "$argon2id$hashed";
  private static final String REVOKE_KEY = "pin-token:revoke:" + CUSTOMER_ID;

  @BeforeEach
  void setUp() throws Exception {
    // PinTokenProperties 기본값으로 리플렉션 주입 (sessionToken.ttlSeconds=180)
    PinTokenProperties props = new PinTokenProperties();
    java.lang.reflect.Field propsField =
        PinAuthService.class.getDeclaredField("pinTokenProperties");
    propsField.setAccessible(true);
    propsField.set(pinAuthService, props);
  }

  // === P-7: 성공·무변화일 때 UPDATE 쿼리가 발생하지 않음 ===

  @Test
  @DisplayName("P-7: PIN 성공 시 failedCount=0, lockedUntil=null이면 save()를 호출하지 않는다")
  void verify_success_no_update_when_already_clean() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(0)          // 이미 정상
            .lockedUntil(null)       // 잠금 없음
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // save()가 호출되지 않아야 한다 — 불필요한 UPDATE 제거 확인
    verify(customerPinAuthRepository, never()).save(any());
  }

  @Test
  @DisplayName("P-7: PIN 성공 시 failedCount>0이면 save()를 호출하여 초기화한다")
  void verify_success_updates_when_failed_count_nonzero() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(3)          // 이전 실패가 있었음
            .lockedUntil(null)
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // failedCount를 초기화해야 하므로 save() 호출
    verify(customerPinAuthRepository).save(row);
    assertThat(row.getFailedCount()).isZero();
  }

  @Test
  @DisplayName("P-7: PIN 성공 시 lockedUntil이 과거값(잠금 해제됨)이면 save()를 호출하여 null로 정리한다")
  void verify_success_updates_when_locked_until_is_past() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(0)
            .lockedUntil(LocalDateTime.of(2026, 8, 23, 9, 50))  // 과거 — 잠금 해제됨
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // lockedUntil이 non-null이므로 정리를 위해 save() 호출
    verify(customerPinAuthRepository).save(row);
    assertThat(row.getLockedUntil()).isNull();
  }

  // ===== 신규 테스트 (T-16, T-17) — 세트 #41 revocation 기록 =====

  /**
   * T-16: setOrUpdatePin 성공 시 pin-token:revoke:{customerId} 에 SET 호출.
   *
   * <p>TTL은 session-token.ttl-seconds(180) + 30 = 210초.
   */
  @Test
  @DisplayName("T-16: setOrUpdatePin 성공 시 pin-token:revoke:{customerId}에 epoch 초 SET 호출")
  void t16_set_or_update_pin_records_revocation_in_redis() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
    when(passwordEncoder.encode(RAW_PIN)).thenReturn(PIN_HASH);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    pinAuthService.setOrUpdatePin(CUSTOMER_ID, RAW_PIN);

    // 저장소 save 호출
    verify(customerPinAuthRepository).save(any(CustomerPinAuth.class));

    // Redis SET 호출: key=pin-token:revoke:42, value=epoch초 문자열, ttl=210초
    long expectedEpoch = now.getEpochSecond();
    verify(valueOperations).set(
        eq(REVOKE_KEY),
        eq(String.valueOf(expectedEpoch)),
        eq(210L),
        eq(TimeUnit.SECONDS));
  }

  /**
   * T-17: Redis가 예외를 던져도 PIN 변경은 성공한다 (롤백 없음).
   *
   * <p>Redis 장애가 PIN 변경을 막으면 그건 우리가 만든 새 장애다.
   * try/catch로 감싸고 warn만 남기는 게 의도된 동작 (§V2-8 비대칭 원칙).
   */
  @Test
  @DisplayName("T-17: Redis가 예외를 던져도 PIN 변경은 성공한다 (롤백 없음)")
  void t17_redis_exception_does_not_rollback_pin_change() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
    when(passwordEncoder.encode(RAW_PIN)).thenReturn(PIN_HASH);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    // Redis SET이 예외를 던지도록 설정
    doThrow(new RuntimeException("Redis connection refused"))
        .when(valueOperations)
        .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

    // 예외가 전파되지 않아야 한다 — PIN 변경은 성공
    pinAuthService.setOrUpdatePin(CUSTOMER_ID, RAW_PIN);

    // DB save는 정상 호출됨
    verify(customerPinAuthRepository).save(any(CustomerPinAuth.class));
  }
}
