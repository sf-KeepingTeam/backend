package com.ssafy.keeping.domain.auth.pin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * PinRateLimiter 단위 테스트.
 *
 * <p>실행 금지 — 작성만 (세트 #43 제약). 인프라 담당이 재측정 시 실행.
 */
@ExtendWith(MockitoExtension.class)
class PinRateLimiterTest {

  private static final Long CUSTOMER_ID = 42L;
  private static final Long OTHER_CUSTOMER_ID = 99L;

  // 고정 시각: 2026-01-01 00:00:00 UTC → epochSecond = 1735689600
  // epochMinute = 1735689600 / 60 = 28928160
  private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
  private static final long EPOCH_MINUTE = FIXED_INSTANT.getEpochSecond() / 60; // 28928160

  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private PinTokenProperties properties;
  private Clock clock;
  private PinRateLimiter pinRateLimiter;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    properties = new PinTokenProperties();
    // rateLimit 기본값: enabled=true, perMinute=10

    lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

    pinRateLimiter = new PinRateLimiter(stringRedisTemplate, properties, clock);
  }

  /**
   * R-1: enabled=false 면 Redis 호출 0건, 예외 없음.
   */
  @Test
  @DisplayName("R-1: enabled=false 이면 Redis를 전혀 호출하지 않고 통과한다")
  void r1_disabled_no_redis_call() {
    properties.getRateLimit().setEnabled(false);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();

    verify(stringRedisTemplate, never()).opsForValue();
  }

  /**
   * R-2: INCR 결과가 1 이면 EXPIRE 60초가 호출된다 (새 창).
   */
  @Test
  @DisplayName("R-2: INCR 결과가 1 이면 EXPIRE 60초가 호출된다")
  void r2_first_increment_sets_expire() {
    String expectedKey = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;
    when(valueOps.increment(expectedKey)).thenReturn(1L);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();

    verify(stringRedisTemplate).expire(expectedKey, 60L, TimeUnit.SECONDS);
  }

  /**
   * R-3: INCR 결과가 2 이면 EXPIRE 호출 안 됨 (TTL 갱신 금지 — 갱신하면 창이 무한히 밀림).
   */
  @Test
  @DisplayName("R-3: INCR 결과가 2 이면 EXPIRE를 호출하지 않는다 (창 연장 금지)")
  void r3_subsequent_increment_no_expire() {
    String expectedKey = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;
    when(valueOps.increment(expectedKey)).thenReturn(2L);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();

    verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
  }

  /**
   * R-4: INCR 결과가 10 (perMinute=10) — 경계값: 이하는 통과.
   */
  @Test
  @DisplayName("R-4: INCR 결과가 perMinute 과 같으면(=10) 통과한다 (경계값)")
  void r4_at_limit_passes() {
    String expectedKey = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;
    when(valueOps.increment(expectedKey)).thenReturn(10L);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();
  }

  /**
   * R-5: INCR 결과가 11 — 상한 초과 → PIN_TOKEN_RATE_LIMITED (429).
   */
  @Test
  @DisplayName("R-5: INCR 결과가 perMinute 초과(11)이면 PIN_TOKEN_RATE_LIMITED 예외")
  void r5_over_limit_throws_rate_limited() {
    String expectedKey = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;
    when(valueOps.increment(expectedKey)).thenReturn(11L);

    assertThatThrownBy(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PIN_TOKEN_RATE_LIMITED);
  }

  /**
   * R-6: Redis가 예외를 던지면 통과한다 (예외 전파 안 함).
   *
   * <p>이 클래스의 Redis는 속도 제한용이다. 장애가 나도 공격자는 여전히 올바른 PIN을 알아야
   * Argon2가 실행된다. Redis 장애로 정상 고객 결제가 전부 막히는 것이 더 나쁘다.
   */
  @Test
  @DisplayName("R-6: Redis가 예외를 던지면 예외를 전파하지 않고 통과한다 (장애 시 허용)")
  void r6_redis_exception_passes_through() {
    when(valueOps.increment(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();
  }

  /**
   * R-7: 서로 다른 customerId 는 키가 다르다.
   */
  @Test
  @DisplayName("R-7: 서로 다른 customerId 는 서로 다른 Redis 키를 사용한다")
  void r7_different_customer_ids_use_different_keys() {
    String keyA = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;
    String keyB = "pin-token:rate:" + OTHER_CUSTOMER_ID + ":" + EPOCH_MINUTE;

    when(valueOps.increment(keyA)).thenReturn(1L);
    when(valueOps.increment(keyB)).thenReturn(1L);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();
    assertThatCode(() -> pinRateLimiter.checkAndIncrement(OTHER_CUSTOMER_ID))
        .doesNotThrowAnyException();

    verify(valueOps).increment(keyA);
    verify(valueOps).increment(keyB);
  }

  /**
   * R-8: 같은 분 안의 두 호출은 같은 키를 사용한다.
   */
  @Test
  @DisplayName("R-8: 같은 분 안의 두 호출은 같은 Redis 키를 사용한다")
  void r8_same_minute_same_key() {
    String expectedKey = "pin-token:rate:" + CUSTOMER_ID + ":" + EPOCH_MINUTE;

    when(valueOps.increment(expectedKey)).thenReturn(1L, 2L);

    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();
    assertThatCode(() -> pinRateLimiter.checkAndIncrement(CUSTOMER_ID))
        .doesNotThrowAnyException();

    // 동일 키로 두 번 INCR
    verify(valueOps, times(2)).increment(expectedKey);
    // 첫 번째 호출(count=1)에만 EXPIRE
    verify(stringRedisTemplate, times(1)).expire(eq(expectedKey), eq(60L), eq(TimeUnit.SECONDS));
  }
}
